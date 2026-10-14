package com.api.decouper.game;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.api.decouper.api.*;
import com.api.decouper.autoconfigure.DecouperProperties;
import com.api.decouper.core.*;
import com.api.decouper.entity.TaskStatus;
import com.api.decouper.examples.game.*;
import com.api.decouper.observability.RuntimeSignals;
import com.api.decouper.samples.support.MySqlFixture;
import com.api.decouper.spi.*;
import com.infeed.spi.annotation.BizSpi;
import com.infeed.spi.core.container.ISpiContainer;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class GameRechargeScenarioIT extends MySqlFixture {
  SimulatedPaymentChannel channel;
  HttpPaymentChannelClient client;
  GameRechargeService game;
  GameRechargeHandler handler;
  TaskExecutorService engine;
  DecouperProperties config;
  RuntimeSignals signals;
  BlockingQueue<Long> messages;

  @BeforeEach
  void gameSetup() throws Exception {
    new ResourceDatabasePopulator(new ClassPathResource("db/game-recharge.sql")).execute(ds);
    jdbc.update("INSERT INTO game_wallet(player_id,coins) VALUES('player-1',0),('player-2',0)");
    channel = new SimulatedPaymentChannel();
    client = new HttpPaymentChannelClient(channel.baseUrl(), 1000, 2000);
    messages = new LinkedBlockingQueue<>();
    signals = new RuntimeSignals();
    config = new DecouperProperties();
    config.setRecoveryDelayMillis(20);
    configure(messages::add, false);
  }

  @AfterEach
  void shutdown() {
    if (engine != null) engine.close();
    if (channel != null) channel.close();
  }

  void configure(TaskMessagePublisher publisher, boolean failApplyOnce) {
    if (engine != null) engine.close();
    game =
        new GameRechargeService(
            jdbc, tm, new TaskSubmissionService(repo, publisher, signals), client);
    handler =
        failApplyOnce
            ? new FailingOnceHandler(game, client)
            : new GameRechargeHandler(game, client);
    engine = new TaskExecutorService(repo, resolver(handler), config, signals);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  HandlerResolver resolver(GameRechargeHandler h) {
    // Only the JVM-global container is isolated; HTTP, handler, executor, SPI resolver and database
    // are real.
    ISpiContainer container = mock(ISpiContainer.class);
    doReturn(Collections.singletonMap("GAME_RECHARGE:v1", h))
        .when(container)
        .lookup(TaskHandler.class);
    return new InfeedHandlerResolver(container, Collections.singletonList(h));
  }

  GameRechargeService.Receipt create(String order) {
    return game.createRecharge(order, "player-1", "COINS_1000");
  }

  String state(String order) {
    return jdbc.queryForObject(
        "SELECT status FROM game_recharge_order WHERE order_id=?", String.class, order);
  }

  long coins() {
    return jdbc.queryForObject(
        "SELECT coins FROM game_wallet WHERE player_id='player-1'", Long.class);
  }

  void due(long id) {
    jdbc.update("UPDATE local_task SET next_execute_time=0 WHERE id=?", id);
  }

  void done(long id) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (repo.findById(id).getStatus() != TaskStatus.DONE && System.nanoTime() < deadline)
      Thread.sleep(10);
    assertEquals(TaskStatus.DONE, repo.findById(id).getStatus());
  }

  @Test
  void localCommitThenMqCreatesChannelPaymentAndConfirmedPaymentCreditsOnce() throws Exception {
    GameRechargeService.Receipt receipt = create("normal");
    assertEquals("NEW", state("normal"));
    assertEquals(TaskStatus.READY, repo.findById(receipt.taskId).getStatus());
    assertEquals(0, channel.createCalls.get());
    assertEquals(receipt.taskId, messages.take().longValue());
    engine.executeTask(receipt.taskId);
    assertEquals("ACCEPTED", state("normal"));
    assertEquals(0, coins());
    assertEquals(1, channel.createCalls.get());
    channel.markPaid("normal");
    game.confirmPayment("normal");
    game.confirmPayment("normal");
    assertEquals("PAID", state("normal"));
    assertEquals(1000, coins());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM game_credit_ledger", Integer.class));
    assertEquals(new BigDecimal("10.01"), channel.lastRequest.amount);
  }

  @Test
  void rolledBackGameOrderLeavesNoTaskNoMessageAndNoChannelRequest() {
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.execute(
                st -> {
                  create("rollback");
                  throw new IllegalStateException("game transaction aborted");
                }));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM game_recharge_order", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM local_task", Integer.class));
    assertTrue(messages.isEmpty());
    assertEquals(0, channel.createCalls.get());
  }

  @Test
  void unavailableMqIsRecoveredByPeriodicPolling() throws Exception {
    configure(
        id -> {
          throw new IllegalStateException("mq down");
        },
        false);
    GameRechargeService.Receipt receipt = create("mq-down");
    config.setPollIntervalMillis(30);
    try (TaskScheduler scheduler = new TaskScheduler(repo, engine, config, signals)) {
      scheduler.start();
      done(receipt.taskId);
    }
    assertEquals("ACCEPTED", state("mq-down"));
    assertEquals(1, channel.createCalls.get());
    assertEquals(0, coins());
  }

  @Test
  void realHttpReadTimeoutQueriesAfterRestartWithoutResubmittingPayment() throws Exception {
    channel.mode = SimulatedPaymentChannel.Mode.ACCEPT_THEN_TIMEOUT;
    client = new HttpPaymentChannelClient(channel.baseUrl(), 1000, 150);
    configure(messages::add, false);
    GameRechargeService.Receipt receipt = create("timeout");
    engine.executeTask(receipt.taskId);
    assertEquals(TaskStatus.UNKNOWN, repo.findById(receipt.taskId).getStatus());
    assertEquals("NEW", state("timeout"));
    channel.mode = SimulatedPaymentChannel.Mode.ACCEPT;
    engine.close();
    engine = new TaskExecutorService(repo, resolver(handler), config, signals);
    due(receipt.taskId);
    engine.executeTask(receipt.taskId);
    assertEquals(TaskStatus.DONE, repo.findById(receipt.taskId).getStatus());
    assertEquals("ACCEPTED", state("timeout"));
    assertEquals(1, channel.createCalls.get());
    assertEquals(1, channel.queryCalls.get());
    assertEquals(0, coins());
  }

  @Test
  void localApplyFailureRollsBackAndOnlyReappliesDurableResult() throws Exception {
    configure(messages::add, true);
    GameRechargeService.Receipt receipt = create("apply-fail");
    engine.executeTask(receipt.taskId);
    assertEquals("NEW", state("apply-fail"));
    assertEquals(TaskStatus.RESULT_READY, repo.findById(receipt.taskId).getStatus());
    due(receipt.taskId);
    engine.executeTask(receipt.taskId);
    assertEquals("ACCEPTED", state("apply-fail"));
    assertEquals(TaskStatus.DONE, repo.findById(receipt.taskId).getStatus());
    assertEquals(1, channel.createCalls.get());
  }

  @Test
  void duplicateMessagesAndIndependentCompensationCompeteThroughCas() throws Exception {
    GameRechargeService.Receipt receipt = create("race");
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try (TaskExecutorService second =
        new TaskExecutorService(repo, resolver(handler), config, signals)) {
      CountDownLatch start = new CountDownLatch(1);
      Future<?> a =
          workers.submit(
              () -> {
                start.await();
                return engine.executeTask(receipt.taskId);
              });
      Future<?> b =
          workers.submit(
              () -> {
                start.await();
                return second.executeTask(receipt.taskId);
              });
      start.countDown();
      a.get(5, TimeUnit.SECONDS);
      b.get(5, TimeUnit.SECONDS);
      engine.executeTask(receipt.taskId);
      assertEquals(1, channel.createCalls.get());
      assertEquals("ACCEPTED", state("race"));
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void paidNotificationBeforeInitiationResultCannotBeOverwrittenOrCreditTwice() throws Exception {
    channel.onAccepted =
        order -> {
          channel.markPaid(order);
          game.confirmPayment(order);
        };
    GameRechargeService.Receipt receipt = create("early-paid");
    engine.executeTask(receipt.taskId);
    assertEquals(TaskStatus.DONE, repo.findById(receipt.taskId).getStatus());
    assertEquals("PAID", state("early-paid"));
    assertEquals(1000, coins());
    game.confirmPayment("early-paid");
    assertEquals(1000, coins());
  }

  @Test
  void definitiveChannelDeclineCompletesFailureWithoutCrediting() throws Exception {
    channel.mode = SimulatedPaymentChannel.Mode.DECLINE;
    GameRechargeService.Receipt receipt = create("decline");
    engine.executeTask(receipt.taskId);
    assertEquals(TaskStatus.DONE, repo.findById(receipt.taskId).getStatus());
    assertEquals("FAILURE", repo.findById(receipt.taskId).getResultOutcome());
    assertEquals("FAILED", state("decline"));
    assertEquals(0, coins());
  }

  @Test
  void inconclusiveQueryStopsForManualReviewInsteadOfBlindRetry() throws Exception {
    channel.mode = SimulatedPaymentChannel.Mode.UNCLASSIFIED;
    GameRechargeService.Receipt receipt = create("unknown");
    engine.executeTask(receipt.taskId);
    due(receipt.taskId);
    engine.executeTask(receipt.taskId);
    assertEquals(TaskStatus.MANUAL, repo.findById(receipt.taskId).getStatus());
    assertEquals(1, channel.createCalls.get());
    assertEquals(1, channel.queryCalls.get());
    assertEquals(0, coins());
  }

  @Test
  void onlyExplicitNotAcceptedResponseAuthorizesSafeRetryWithSameKey() throws Exception {
    channel.mode = SimulatedPaymentChannel.Mode.NOT_ACCEPTED_ONCE;
    GameRechargeService.Receipt receipt = create("retry");
    engine.executeTask(receipt.taskId);
    assertEquals(TaskStatus.RETRY_WAIT, repo.findById(receipt.taskId).getStatus());
    due(receipt.taskId);
    engine.executeTask(receipt.taskId);
    assertEquals("ACCEPTED", state("retry"));
    assertEquals(2, channel.createCalls.get());
    assertEquals(1, new HashSet<>(channel.receivedKeys).size());
    assertEquals(0, coins());
  }

  @Test
  void mismatchedPaidAmountCannotCreditWallet() throws Exception {
    GameRechargeService.Receipt receipt = create("amount");
    engine.executeTask(receipt.taskId);
    channel.markPaid("amount");
    channel.overrideAmount("amount", new BigDecimal("0.01"));
    assertThrows(IllegalStateException.class, () -> game.confirmPayment("amount"));
    assertEquals("ACCEPTED", state("amount"));
    assertEquals(0, coins());
  }

  @Test
  void repeatedGameRequestIsIdempotentAndCannotChangePlayer() {
    GameRechargeService.Receipt a = create("duplicate"), b = create("duplicate");
    assertEquals(a.taskId, b.taskId);
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM game_recharge_order", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM local_task", Integer.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> game.createRecharge("duplicate", "player-2", "COINS_1000"));
    assertEquals(0, channel.createCalls.get());
  }

  @Test
  void recoveredPaidResultCreditsWithoutNeedingAnotherNotification() throws Exception {
    channel.mode = SimulatedPaymentChannel.Mode.ACCEPT_THEN_TIMEOUT;
    client = new HttpPaymentChannelClient(channel.baseUrl(), 1000, 150);
    configure(messages::add, false);
    GameRechargeService.Receipt receipt = create("paid-recovery");
    engine.executeTask(receipt.taskId);
    assertEquals(TaskStatus.UNKNOWN, repo.findById(receipt.taskId).getStatus());
    channel.markPaid("paid-recovery");
    due(receipt.taskId);
    engine.executeTask(receipt.taskId);
    assertEquals("PAID", state("paid-recovery"));
    assertEquals(1000, coins());
    assertEquals(1, channel.createCalls.get());
    assertEquals(TaskStatus.DONE, repo.findById(receipt.taskId).getStatus());
  }

  @Test
  void creditLedgerWalletOrderAndTaskRollbackTogetherOnLocalFailure() throws Exception {
    channel.mode = SimulatedPaymentChannel.Mode.IMMEDIATELY_PAID;
    configure(messages::add, true);
    GameRechargeService.Receipt receipt = create("credit-rollback");
    engine.executeTask(receipt.taskId);
    assertEquals(TaskStatus.RESULT_READY, repo.findById(receipt.taskId).getStatus());
    assertEquals("NEW", state("credit-rollback"));
    assertEquals(0, coins());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM game_credit_ledger", Integer.class));
    due(receipt.taskId);
    engine.executeTask(receipt.taskId);
    assertEquals("PAID", state("credit-rollback"));
    assertEquals(1000, coins());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM game_credit_ledger", Integer.class));
    assertEquals(1, channel.createCalls.get());
    assertEquals(TaskStatus.DONE, repo.findById(receipt.taskId).getStatus());
  }

  @Test
  void concurrentConfirmedNotificationsCreditPlayerOnlyOnce() throws Exception {
    GameRechargeService.Receipt receipt = create("concurrent-credit");
    engine.executeTask(receipt.taskId);
    channel.markPaid("concurrent-credit");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Callable<Boolean> pay =
          () -> {
            start.await();
            return game.confirmPayment("concurrent-credit");
          };
      Future<Boolean> a = pool.submit(pay), b = pool.submit(pay);
      start.countDown();
      assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
      assertEquals(1000, coins());
      assertEquals(
          1, jdbc.queryForObject("SELECT COUNT(*) FROM game_credit_ledger", Integer.class));
    } finally {
      pool.shutdownNow();
    }
  }

  @BizSpi(name = "GAME_RECHARGE:v1")
  static class FailingOnceHandler extends GameRechargeHandler {
    AtomicBoolean first = new AtomicBoolean(true);

    FailingOnceHandler(GameRechargeService service, PaymentChannelClient client) {
      super(service, client);
    }

    public void applyResult(
        TaskContext c, RechargeRequest r, ExecutionResult<ChannelPayment> result) {
      super.applyResult(c, r, result);
      if (first.getAndSet(false)) throw new IllegalStateException("simulated local commit failure");
    }
  }
}
