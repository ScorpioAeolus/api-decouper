package com.api.decouper.examples.game;

import java.math.BigDecimal;

/** Versioned snapshot from the server-side game product catalog. */
public class RechargeRequest {
  public String orderId;
  public String playerId;
  public String sku;
  public BigDecimal amount;
  public String currency;
  public long creditUnits;

  public RechargeRequest() {}

  public RechargeRequest(
      String id, String player, String sku, BigDecimal amount, String currency, long units) {
    this.orderId = id;
    this.playerId = player;
    this.sku = sku;
    this.amount = amount;
    this.currency = currency;
    this.creditUnits = units;
  }
}
