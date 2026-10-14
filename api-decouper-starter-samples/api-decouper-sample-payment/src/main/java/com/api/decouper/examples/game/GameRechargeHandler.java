package com.api.decouper.examples.game;

import com.api.decouper.api.*;
import com.api.decouper.spi.TaskHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infeed.spi.annotation.BizSpi;
import java.time.Instant;

@BizSpi(name = "GAME_RECHARGE:v1")
public class GameRechargeHandler implements TaskHandler<RechargeRequest, ChannelPayment> {
  private final GameRechargeService game;
  private final PaymentChannelClient channel;
  private final ObjectMapper json = new ObjectMapper();

  public GameRechargeHandler(GameRechargeService game, PaymentChannelClient channel) {
    this.game = game;
    this.channel = channel;
  }

  public ExecutionResult<ChannelPayment> execute(TaskContext context, RechargeRequest request)
      throws Exception {
    validate(context, request);
    ChannelPayment reply = channel.create(context.getOperationKey(), request);
    GameRechargeService.verify(request, reply);
    if ("NOT_ACCEPTED_RETRYABLE".equals(reply.state))
      return ExecutionResult.retry(Instant.now().plusSeconds(1), "CHANNEL_CONFIRMED_NOT_ACCEPTED");
    return classify(reply);
  }

  public RecoveryDecision<ChannelPayment> recover(TaskContext context, RechargeRequest request)
      throws Exception {
    validate(context, request);
    ChannelPayment reply = channel.query(request.orderId);
    GameRechargeService.verify(request, reply);
    ExecutionResult<ChannelPayment> result = classify(reply);
    return result.isConfirmed()
        ? RecoveryDecision.confirmed(result)
        : RecoveryDecision.manual("CHANNEL_QUERY_INCONCLUSIVE");
  }

  private ExecutionResult<ChannelPayment> classify(ChannelPayment reply) {
    if ("ACCEPTED".equals(reply.state) || "PAID".equals(reply.state))
      return ExecutionResult.success(reply);
    if ("DECLINED".equals(reply.state)) return ExecutionResult.failure("CHANNEL_DECLINED", reply);
    return ExecutionResult.unknown("CHANNEL_RESULT_UNKNOWN");
  }

  public void applyResult(
      TaskContext context, RechargeRequest request, ExecutionResult<ChannelPayment> result) {
    validate(context, request);
    game.applyInitiation(
        request, result.getValue(), result.getKind() == ExecutionResult.Kind.SUCCESS);
  }

  private void validate(TaskContext c, RechargeRequest r) {
    if (r == null
        || !c.getBizId().equals(r.orderId)
        || r.amount == null
        || r.amount.signum() <= 0
        || r.creditUnits <= 0) throw new IllegalArgumentException("Invalid recharge snapshot");
  }

  public TaskCodec<RechargeRequest, ChannelPayment> codec() {
    return new TaskCodec<RechargeRequest, ChannelPayment>() {
      public RechargeRequest decodeRequest(String payload, int version) {
        version(version);
        try {
          return json.readValue(payload, RechargeRequest.class);
        } catch (Exception e) {
          throw new IllegalArgumentException("Invalid game recharge JSON", e);
        }
      }

      public String encodeResult(ChannelPayment result) {
        try {
          return json.writeValueAsString(result);
        } catch (Exception e) {
          throw new IllegalArgumentException("Cannot encode channel payment", e);
        }
      }

      public ChannelPayment decodeResult(String payload, int version) {
        version(version);
        try {
          return json.readValue(payload, ChannelPayment.class);
        } catch (Exception e) {
          throw new IllegalArgumentException("Invalid channel payment JSON", e);
        }
      }

      private void version(int version) {
        if (version != 1)
          throw new IllegalArgumentException("Unsupported game recharge schema version");
      }
    };
  }
}
