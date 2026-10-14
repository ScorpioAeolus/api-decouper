package com.api.decouper.examples.game;

import com.api.decouper.api.*;
import com.api.decouper.core.TaskSubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Game-local business state. All balance changes remain the host business's responsibility. */
public class GameRechargeService {
  private static final BigDecimal PRICE = new BigDecimal("10.01");
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final TaskSubmissionService tasks;
  private final PaymentChannelClient channel;
  private final ObjectMapper json = new ObjectMapper();

  public GameRechargeService(
      JdbcTemplate jdbc,
      DataSourceTransactionManager manager,
      TaskSubmissionService tasks,
      PaymentChannelClient channel) {
    if (jdbc.getDataSource() != manager.getDataSource())
      throw new IllegalArgumentException(
          "Game and component must use the same transaction datasource");
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(manager);
    this.tasks = tasks;
    this.channel = channel;
  }

  public static final class Receipt {
    public final String orderId;
    public final long taskId;

    public Receipt(String id, long task) {
      orderId = id;
      taskId = task;
    }
  }

  public Receipt createRecharge(String orderId, String playerId, String sku) {
    Checks.text(orderId, 80, "orderId");
    Checks.text(playerId, 80, "playerId");
    if (!"COINS_1000".equals(sku))
      throw new IllegalArgumentException("Unknown server-side product");
    return tx.execute(
        status -> {
          if (jdbc.queryForObject(
                  "SELECT COUNT(*) FROM game_wallet WHERE player_id=?", Integer.class, playerId)
              != 1) throw new IllegalArgumentException("Unknown player");
          RechargeRequest snapshot =
              new RechargeRequest(orderId, playerId, sku, PRICE, "CNY", 1000);
          String payload;
          try {
            payload = json.writeValueAsString(snapshot);
          } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode game recharge", e);
          }
          // Take the task row before the business row, matching the component's apply transaction
          // lock order.
          // Both writes still commit atomically; uncommitted tasks cannot be picked up by another
          // node.
          long taskId =
              tasks.submit(
                  new TaskRequest(
                      "GAME_RECHARGE", 1, orderId, "GAME_RECHARGE:" + orderId, payload, 1));
          jdbc.update(
              "INSERT INTO game_recharge_order(order_id,player_id,sku,amount,currency,credit_units,status) VALUES(?,?,?,?,'CNY',1000,'NEW') ON DUPLICATE KEY UPDATE order_id=order_id",
              orderId,
              playerId,
              sku,
              PRICE);
          RechargeRequest stored = load(orderId, true);
          if (!playerId.equals(stored.playerId)
              || !sku.equals(stored.sku)
              || stored.amount.compareTo(PRICE) != 0
              || !"CNY".equals(stored.currency)
              || stored.creditUnits != 1000)
            throw new IllegalArgumentException(
                "Recharge identity reused for different player/product");
          return new Receipt(orderId, taskId);
        });
  }
  /** Called by the handler inside the component's result-application transaction. */
  public void applyInitiation(RechargeRequest request, ChannelPayment result, boolean accepted) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()
        || !org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(
            jdbc.getDataSource()))
      throw new IllegalStateException("Local result transaction required");
    verify(request, result);
    RechargeRequest locked = load(request.orderId, true);
    verify(locked, result);
    if (accepted && "PAID".equals(result.state)) {
      creditConfirmed(locked, result);
      return;
    }
    String next = accepted ? "ACCEPTED" : "FAILED";
    int changed =
        jdbc.update(
            "UPDATE game_recharge_order SET status=?,channel_reference=? WHERE order_id=? AND status='NEW'",
            next,
            result.reference,
            request.orderId);
    if (changed == 0) {
      String current =
          jdbc.queryForObject(
              "SELECT status FROM game_recharge_order WHERE order_id=?",
              String.class,
              request.orderId);
      if (!next.equals(current) && !"PAID".equals(current))
        throw new IllegalStateException("Conflicting local recharge result");
    }
  }
  /**
   * Untrusted notifications only trigger a query. Credit requires an independently confirmed
   * matching channel result.
   */
  public boolean confirmPayment(String orderId) throws Exception {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Query channel before opening local credit transaction");
    RechargeRequest snapshot = load(orderId, false);
    ChannelPayment result = channel.query(orderId);
    verify(snapshot, result);
    if (!"PAID".equals(result.state)) return false;
    return tx.execute(status -> creditConfirmed(load(orderId, true), result));
  }

  private boolean creditConfirmed(RechargeRequest locked, ChannelPayment result) {
    verify(locked, result);
    String current =
        jdbc.queryForObject(
            "SELECT status FROM game_recharge_order WHERE order_id=?",
            String.class,
            locked.orderId);
    if ("PAID".equals(current)) return false;
    if (!"NEW".equals(current) && !"ACCEPTED".equals(current))
      throw new IllegalStateException("Cannot credit terminal failed recharge");
    jdbc.update(
        "INSERT INTO game_credit_ledger(order_id,player_id,credit_units) VALUES(?,?,?)",
        locked.orderId,
        locked.playerId,
        locked.creditUnits);
    if (jdbc.update(
            "UPDATE game_wallet SET coins=coins+? WHERE player_id=?",
            locked.creditUnits,
            locked.playerId)
        != 1) throw new IllegalStateException("Player wallet missing");
    if (jdbc.update(
            "UPDATE game_recharge_order SET status='PAID',channel_reference=? WHERE order_id=? AND status IN ('NEW','ACCEPTED')",
            result.reference,
            locked.orderId)
        != 1) throw new IllegalStateException("Recharge state changed");
    return true;
  }

  public static void verify(RechargeRequest request, ChannelPayment result) {
    if (result == null
        || !Objects.equals(request.orderId, result.merchantOrderId)
        || result.amount == null
        || request.amount.compareTo(result.amount) != 0
        || !Objects.equals(request.currency, result.currency))
      throw new IllegalStateException("Channel payment identity/amount/currency mismatch");
    if (Arrays.asList("ACCEPTED", "PAID").contains(result.state)
        && (result.reference == null || result.reference.trim().isEmpty()))
      throw new IllegalStateException("Missing channel reference");
  }

  private RechargeRequest load(String id, boolean lock) {
    return jdbc.queryForObject(
        "SELECT order_id,player_id,sku,amount,currency,credit_units FROM game_recharge_order WHERE order_id=?"
            + (lock ? " FOR UPDATE" : ""),
        mapper,
        id);
  }

  private static final RowMapper<RechargeRequest> mapper =
      (rs, n) ->
          new RechargeRequest(
              rs.getString("order_id"),
              rs.getString("player_id"),
              rs.getString("sku"),
              rs.getBigDecimal("amount"),
              rs.getString("currency"),
              rs.getLong("credit_units"));
}
