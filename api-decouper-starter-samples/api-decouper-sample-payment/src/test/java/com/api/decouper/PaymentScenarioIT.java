package com.api.decouper;

import static org.junit.jupiter.api.Assertions.*;

import com.api.decouper.api.*;
import com.api.decouper.autoconfigure.*;
import com.api.decouper.core.*;
import com.api.decouper.entity.*;
import com.api.decouper.examples.PaymentExample.*;
import com.api.decouper.observability.*;
import com.api.decouper.samples.support.MySqlFixture;
import com.api.decouper.spi.InfeedHandlerResolver;
import com.infeed.spi.core.container.ISpiContainer;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PaymentScenarioIT extends MySqlFixture {
  @Test
  void acceptedInitiationIsNotPaidAndLateApplyDoesNotOverwritePaid() {
    AtomicInteger calls = new AtomicInteger();
    PaymentGateway gateway =
        new PaymentGateway() {
          public PaymentReply initiate(String key, PaymentRequest request) {
            assertEquals(new BigDecimal("10.01"), request.amount);
            calls.incrementAndGet();
            return new PaymentReply("ACCEPTED", "ref-1");
          }

          public PaymentReply query(String key) {
            return new PaymentReply("ACCEPTED", "ref-1");
          }
        };
    PaymentInitHandler handler = new PaymentInitHandler(jdbc, gateway);
    // Exercise the actual published infeed-spi container, not a replacement Map.
    ISpiContainer container = ISpiContainer.DEFAULT_INSTANCE;
    container.addToContainer(com.api.decouper.spi.TaskHandler.class, handler);
    InfeedHandlerResolver resolver =
        new InfeedHandlerResolver(container, Collections.singletonList(handler));
    TaskSubmissionService submission = new TaskSubmissionService(repo, null, new RuntimeSignals());
    long first =
        tx.execute(
            st -> {
              jdbc.update("INSERT INTO sample_order VALUES('first','NEW')");
              return submission.submit(
                  new TaskRequest(
                      "PAYMENT_INIT",
                      1,
                      "first",
                      "PAYMENT_INIT:first",
                      "{\"orderId\":\"first\",\"amount\":10.01}",
                      1));
            });
    try (TaskExecutorService engine =
        new TaskExecutorService(repo, resolver, new DecouperProperties(), new RuntimeSignals())) {
      engine.executeTask(first);
      assertEquals(
          "ACCEPTED",
          jdbc.queryForObject("SELECT status FROM sample_order WHERE id='first'", String.class));
      long second =
          tx.execute(
              st -> {
                jdbc.update("INSERT INTO sample_order VALUES('second','NEW')");
                return submission.submit(
                    new TaskRequest(
                        "PAYMENT_INIT",
                        1,
                        "second",
                        "PAYMENT_INIT:second",
                        "{\"orderId\":\"second\",\"amount\":10.01}",
                        1));
              });
      jdbc.update("UPDATE sample_order SET status='PAID' WHERE id='second'");
      engine.executeTask(second);
      assertEquals(
          "PAID",
          jdbc.queryForObject("SELECT status FROM sample_order WHERE id='second'", String.class));
      assertEquals(TaskStatus.DONE, repo.findById(second).getStatus());
      assertEquals(2, calls.get());
    }
  }
}
