package com.api.decouper;

import static org.junit.jupiter.api.Assertions.*;

import com.api.decouper.api.*;
import com.api.decouper.autoconfigure.DecouperProperties;
import com.api.decouper.core.*;
import com.api.decouper.entity.*;
import com.api.decouper.observability.RuntimeSignals;
import com.api.decouper.spi.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TaskExecutionIT extends MySqlFixture {
  static class Payment implements TaskHandler<String, String> {
    AtomicInteger calls = new AtomicInteger(),
        applies = new AtomicInteger(),
        queries = new AtomicInteger();
    boolean timeout, failApply, retry;
    Runnable onApply;

    public TaskCodec<String, String> codec() {
      return TaskCodec.strings();
    }

    public ExecutionResult<String> execute(TaskContext c, String p) throws Exception {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      calls.incrementAndGet();
      if (timeout) throw new java.net.SocketTimeoutException("untrusted response details");
      if (retry) return ExecutionResult.retry(Instant.now().plusSeconds(60), "SAFE_IDEMPOTENT");
      return ExecutionResult.success("accepted");
    }

    public RecoveryDecision<String> recover(TaskContext c, String p) {
      queries.incrementAndGet();
      return RecoveryDecision.confirmed(ExecutionResult.success("accepted"));
    }

    public void applyResult(TaskContext c, String p, ExecutionResult<String> r) {
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      applies.incrementAndGet();
      if (onApply != null) onApply.run();
      if (failApply) throw new IllegalStateException("local db temporary");
    }
  }

  long create() {
    return tx.execute(
        st -> repo.insert(new TaskRequest("PAY", 1, "order1", "PAY:order1", "{}", 1)));
  }

  TaskExecutorService engine(Payment h) {
    return new TaskExecutorService(
        repo, (t, v, c) -> h, new DecouperProperties(), new RuntimeSignals());
  }

  void due(long id) {
    jdbc.update("UPDATE local_task SET next_execute_time=0 WHERE id=?", id);
  }

  @Test
  void resultApplyFailureDoesNotRepeatExternalCallAndRollsBackBusiness() {
    long id = create();
    jdbc.update("INSERT INTO sample_order VALUES('order1','NEW')");
    Payment p = new Payment();
    p.failApply = true;
    p.onApply = () -> jdbc.update("UPDATE sample_order SET status='ACCEPTED' WHERE id='order1'");
    try (TaskExecutorService e = engine(p)) {
      e.executeTask(id);
      assertEquals(TaskStatus.RESULT_READY, repo.findById(id).getStatus());
      assertEquals("NEW", jdbc.queryForObject("SELECT status FROM sample_order", String.class));
      p.failApply = false;
      due(id);
      e.executeTask(id);
      assertEquals(TaskStatus.DONE, repo.findById(id).getStatus());
      assertEquals(1, p.calls.get());
      assertEquals(2, p.applies.get());
      assertEquals(
          "ACCEPTED", jdbc.queryForObject("SELECT status FROM sample_order", String.class));
    }
  }

  @Test
  void timeoutGoesToRecoveryNotExternalRetry() {
    long id = create();
    Payment p = new Payment();
    p.timeout = true;
    try (TaskExecutorService e = engine(p)) {
      e.executeTask(id);
      assertEquals(TaskStatus.UNKNOWN, repo.findById(id).getStatus());
      assertEquals(0, p.applies.get());
      due(id);
      e.executeTask(id);
      assertEquals(TaskStatus.DONE, repo.findById(id).getStatus());
      assertEquals(1, p.calls.get());
      assertEquals(1, p.queries.get());
    }
  }

  @Test
  void leaseExpiredExecutionUsesRecoveryAndApplyLeaseUsesOnlyApply() {
    long id = create();
    LocalTask t = repo.claim(repo.findById(id), 1000);
    jdbc.update("UPDATE local_task SET lease_until=0 WHERE id=?", id);
    repo.recoverExpired(repo.findById(id));
    Payment p = new Payment();
    try (TaskExecutorService e = engine(p)) {
      e.executeTask(id);
      assertEquals(0, p.calls.get());
      assertEquals(1, p.queries.get());
      assertEquals(TaskStatus.DONE, repo.findById(id).getStatus());
    }
    assertFalse(repo.saveResult(t, "late", 1, ExecutionResult.Kind.SUCCESS, "LATE"));
  }

  @Test
  void repeatedMqCannotBypassBackoff() {
    long id = create();
    Payment p = new Payment();
    p.retry = true;
    try (TaskExecutorService e = engine(p)) {
      e.executeTask(id);
      e.executeTask(id);
      assertEquals(1, p.calls.get());
      assertEquals(TaskStatus.RETRY_WAIT, repo.findById(id).getStatus());
    }
  }

  @Test
  void concurrentTriggersCallExternalOnlyOnce() throws Exception {
    long id = create();
    Payment p = new Payment();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (TaskExecutorService e = engine(p);
        TaskExecutorService secondNode =
            new TaskExecutorService(
                new com.api.decouper.repository.LocalTaskRepository(
                    new org.springframework.jdbc.core.JdbcTemplate(ds),
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds)),
                (a, b, c) -> p,
                new DecouperProperties(),
                new RuntimeSignals())) {
      CountDownLatch start = new CountDownLatch(1);
      Callable<TaskTriggerResult> c =
          () -> {
            start.await();
            return e.executeTask(id);
          };
      Future<?> a = pool.submit(c),
          b =
              pool.submit(
                  () -> {
                    start.await();
                    return secondNode.executeTask(id);
                  });
      start.countDown();
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
      assertEquals(1, p.calls.get());
      assertEquals(TaskStatus.DONE, repo.findById(id).getStatus());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void applyingRowLockPreventsLeaseReclaimerFromOverwritingCommit() throws Exception {
    long id = create();
    Payment p = new Payment();
    CountDownLatch applying = new CountDownLatch(1), release = new CountDownLatch(1);
    p.onApply =
        () -> {
          applying.countDown();
          try {
            assertTrue(release.await(5, TimeUnit.SECONDS));
          } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
          }
        };
    ExecutorService workers = Executors.newFixedThreadPool(2);
    DecouperProperties shortLease = new DecouperProperties();
    shortLease.setLeaseMillis(300);
    try (TaskExecutorService e =
        new TaskExecutorService(repo, (a, b, c) -> p, shortLease, new RuntimeSignals())) {
      Future<?> exec = workers.submit(() -> e.executeTask(id));
      assertTrue(applying.await(5, TimeUnit.SECONDS));
      LocalTask snapshot = repo.findById(id);
      long expiryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (repo.dbNow().toEpochMilli() <= snapshot.getLeaseUntil()
          && System.nanoTime() < expiryDeadline) Thread.yield();
      Future<Boolean> reclaim = workers.submit(() -> repo.recoverExpired(snapshot));
      assertThrows(TimeoutException.class, () -> reclaim.get(100, TimeUnit.MILLISECONDS));
      release.countDown();
      exec.get(10, TimeUnit.SECONDS);
      assertFalse(reclaim.get(10, TimeUnit.SECONDS));
      assertEquals(TaskStatus.DONE, repo.findById(id).getStatus());
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void invalidResultAndMissingRouteStopSafely() {
    long id = create();
    Payment p =
        new Payment() {
          public ExecutionResult<String> execute(TaskContext c, String s) {
            return null;
          }
        };
    try (TaskExecutorService e = engine(p)) {
      e.executeTask(id);
      assertEquals(TaskStatus.UNKNOWN, repo.findById(id).getStatus());
    }
  }

  @Test
  void failureIsAppliedAndBudgetNeverBlindlyRepeatsCall() {
    long id = create();
    Payment h =
        new Payment() {
          public ExecutionResult<String> execute(TaskContext c, String s) {
            calls.incrementAndGet();
            return ExecutionResult.failure("DECLINED", "decline-ref");
          }
        };
    try (TaskExecutorService e = engine(h)) {
      e.executeTask(id);
      assertEquals(TaskStatus.DONE, repo.findById(id).getStatus());
      assertEquals("FAILURE", repo.findById(id).getResultOutcome());
      assertEquals(1, h.applies.get());
    }
  }

  @Test
  void exhaustedRetryBudgetBecomesManualWithoutExtraApiCall() {
    long id = create();
    Payment h = new Payment();
    h.retry = true;
    DecouperProperties p = new DecouperProperties();
    p.setMaxExecuteAttempts(1);
    try (TaskExecutorService e =
        new TaskExecutorService(repo, (a, b, c) -> h, p, new RuntimeSignals())) {
      e.executeTask(id);
      due(id);
      e.executeTask(id);
      assertEquals(TaskStatus.MANUAL, repo.findById(id).getStatus());
      assertEquals(1, h.calls.get());
    }
  }

  @Test
  void failedResultPersistenceLeavesExecutionForRecovery() {
    long id = create();
    Payment h = new Payment();
    com.api.decouper.repository.LocalTaskRepository failing =
        new com.api.decouper.repository.LocalTaskRepository(jdbc, tm) {
          public boolean saveResult(
              LocalTask t, String data, int v, ExecutionResult.Kind outcome, String reason) {
            throw new org.springframework.dao.DataAccessResourceFailureException("db unavailable");
          }
        };
    try (TaskExecutorService e =
        new TaskExecutorService(
            failing, (a, b, c) -> h, new DecouperProperties(), new RuntimeSignals())) {
      assertThrows(
          org.springframework.dao.DataAccessResourceFailureException.class,
          () -> e.executeTask(id));
      assertEquals(TaskStatus.EXECUTING, repo.findById(id).getStatus());
      assertEquals(0, h.applies.get());
      assertEquals(1, h.calls.get());
    }
  }

  @Test
  void lostOwnershipDuringApplyRollsBackBusinessAndTaskWrites() {
    long id = create();
    jdbc.update("INSERT INTO sample_order VALUES('order1','NEW')");
    Payment h = new Payment();
    h.onApply =
        () -> {
          jdbc.update("UPDATE sample_order SET status='ACCEPTED' WHERE id='order1'");
          jdbc.update("UPDATE local_task SET execution_token='changed' WHERE id=?", id);
        };
    try (TaskExecutorService e = engine(h)) {
      e.executeTask(id);
      assertEquals("NEW", jdbc.queryForObject("SELECT status FROM sample_order", String.class));
      assertEquals(TaskStatus.RESULT_READY, repo.findById(id).getStatus());
    }
  }

  @Test
  void incompatibleInputStopsBeforeExternalEffect() {
    long id =
        tx.execute(st -> repo.insert(new TaskRequest("PAY", 1, "b", "version-test", "{}", 2)));
    Payment h = new Payment();
    try (TaskExecutorService e = engine(h)) {
      e.executeTask(id);
      assertEquals(TaskStatus.MANUAL, repo.findById(id).getStatus());
      assertEquals("INPUT_CODEC_ERROR", repo.findById(id).getLastErrorCode());
      assertEquals(0, h.calls.get());
    }
  }

  @Test
  void taskDeadlinePreventsNewExternalCallsButDoesNotPretendFailure() {
    long id = create();
    jdbc.update("UPDATE local_task SET create_time=0 WHERE id=?", id);
    Payment h = new Payment();
    try (TaskExecutorService e = engine(h)) {
      e.executeTask(id);
      assertEquals(TaskStatus.MANUAL, repo.findById(id).getStatus());
      assertEquals(0, h.calls.get());
      assertNull(repo.findById(id).getResultOutcome());
    }
  }

  @Test
  void applyBudgetRetainsDurableResultForManualRecovery() {
    long id = create();
    Payment h = new Payment();
    h.failApply = true;
    DecouperProperties p = new DecouperProperties();
    p.setMaxApplyAttempts(1);
    try (TaskExecutorService e =
        new TaskExecutorService(repo, (a, b, c) -> h, p, new RuntimeSignals())) {
      e.executeTask(id);
      due(id);
      e.executeTask(id);
      assertEquals(TaskStatus.MANUAL, repo.findById(id).getStatus());
      assertEquals("accepted", repo.findById(id).getResultPayload());
      assertEquals(1, h.calls.get());
      assertEquals(1, h.applies.get());
    }
  }
}
