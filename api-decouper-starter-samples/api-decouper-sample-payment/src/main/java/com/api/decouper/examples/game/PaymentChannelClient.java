package com.api.decouper.examples.game;

public interface PaymentChannelClient {
  ChannelPayment create(String idempotencyKey, RechargeRequest request) throws Exception;

  ChannelPayment query(String merchantOrderId) throws Exception;
}
