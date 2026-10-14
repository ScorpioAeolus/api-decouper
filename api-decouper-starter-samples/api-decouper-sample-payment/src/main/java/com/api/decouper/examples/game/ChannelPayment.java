package com.api.decouper.examples.game;

import java.math.BigDecimal;

public class ChannelPayment {
  public String merchantOrderId;
  public String state;
  public String reference;
  public BigDecimal amount;
  public String currency;

  public ChannelPayment() {}

  public ChannelPayment(
      String order, String state, String ref, BigDecimal amount, String currency) {
    this.merchantOrderId = order;
    this.state = state;
    this.reference = ref;
    this.amount = amount;
    this.currency = currency;
  }
}
