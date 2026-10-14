package com.api.decouper.examples;

import com.api.decouper.api.*;
import com.api.decouper.spi.TaskHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infeed.spi.annotation.BizSpi;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;

/** Executable host-side example, deliberately excluded from the published component JAR. */
public final class PaymentExample {
  private PaymentExample() {}

  public static class PaymentRequest {
    public String orderId;
    public BigDecimal amount;
  }

  public static class PaymentReply {
    public String state;
    public String reference;

    public PaymentReply() {}

    public PaymentReply(String state, String reference) {
      this.state = state;
      this.reference = reference;
    }
  }

  public interface PaymentGateway {
    PaymentReply initiate(String idempotencyKey, PaymentRequest request) throws Exception;

    PaymentReply query(String idempotencyKey) throws Exception;
  }

  @BizSpi(name = "PAYMENT_INIT:v1")
  public static class PaymentInitHandler implements TaskHandler<PaymentRequest, PaymentReply> {
    private final JdbcTemplate jdbc;
    private final PaymentGateway gateway;
    private final ObjectMapper json = new ObjectMapper();

    public PaymentInitHandler(JdbcTemplate jdbc, PaymentGateway gateway) {
      this.jdbc = jdbc;
      this.gateway = gateway;
    }

    public ExecutionResult<PaymentReply> execute(TaskContext context, PaymentRequest request)
        throws Exception {
      if (!context.getBizId().equals(request.orderId)
          || request.amount == null
          || request.amount.signum() <= 0)
        throw new IllegalArgumentException("invalid payment input");
      return classify(gateway.initiate(context.getOperationKey(), request));
    }

    public RecoveryDecision<PaymentReply> recover(TaskContext context, PaymentRequest request)
        throws Exception {
      PaymentReply reply = gateway.query(context.getOperationKey());
      // A not-found response may be eventually consistent. This example never assumes it authorizes
      // resubmission.
      if (reply == null) return RecoveryDecision.manual("QUERY_NOT_CONCLUSIVE");
      ExecutionResult<PaymentReply> result = classify(reply);
      return result.isConfirmed()
          ? RecoveryDecision.confirmed(result)
          : RecoveryDecision.manual("QUERY_NOT_CONCLUSIVE");
    }

    private ExecutionResult<PaymentReply> classify(PaymentReply reply) {
      if (reply != null && "ACCEPTED".equals(reply.state)) return ExecutionResult.success(reply);
      if (reply != null && "DECLINED".equals(reply.state))
        return ExecutionResult.failure("PAYMENT_DECLINED", reply);
      return ExecutionResult.unknown("UNCLASSIFIED_RESPONSE");
    }

    public void applyResult(
        TaskContext context, PaymentRequest request, ExecutionResult<PaymentReply> result) {
      String next = result.getKind() == ExecutionResult.Kind.SUCCESS ? "ACCEPTED" : "FAILED";
      int changed =
          jdbc.update(
              "UPDATE sample_order SET status=? WHERE id=? AND status='NEW'",
              next,
              context.getBizId());
      if (changed == 0) {
        String current =
            jdbc.queryForObject(
                "SELECT status FROM sample_order WHERE id=?", String.class, context.getBizId());
        // Known final states outrank initiation results; a missing/unrecognized order requires
        // recovery.
        if (!next.equals(current) && !"PAID".equals(current) && !"FAILED".equals(current))
          throw new IllegalStateException("Unexpected local order state");
      }
    }

    public TaskCodec<PaymentRequest, PaymentReply> codec() {
      return new TaskCodec<PaymentRequest, PaymentReply>() {
        public PaymentRequest decodeRequest(String payload, int version) {
          requireVersion(version);
          try {
            return json.readValue(payload, PaymentRequest.class);
          } catch (Exception e) {
            throw new IllegalArgumentException("Invalid payment input JSON", e);
          }
        }

        public String encodeResult(PaymentReply reply) {
          try {
            return json.writeValueAsString(reply);
          } catch (Exception e) {
            throw new IllegalArgumentException("Cannot encode payment result", e);
          }
        }

        public PaymentReply decodeResult(String payload, int version) {
          requireVersion(version);
          try {
            return json.readValue(payload, PaymentReply.class);
          } catch (Exception e) {
            throw new IllegalArgumentException("Invalid payment result JSON", e);
          }
        }

        private void requireVersion(int v) {
          if (v != 1) throw new IllegalArgumentException("Unsupported payment payload version");
        }
      };
    }
  }
}
