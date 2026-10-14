package com.api.decouper.game;

import com.api.decouper.examples.game.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local HTTP protocol simulator: all channel effects remain in memory and no real payment is made.
 */
final class SimulatedPaymentChannel implements AutoCloseable {
  enum Mode {
    ACCEPT,
    ACCEPT_THEN_TIMEOUT,
    DECLINE,
    UNCLASSIFIED,
    NOT_ACCEPTED_ONCE,
    IMMEDIATELY_PAID
  }

  @FunctionalInterface
  interface Notification {
    void accepted(String order) throws Exception;
  }

  volatile Mode mode = Mode.ACCEPT;
  volatile Notification onAccepted;
  volatile RechargeRequest lastRequest;
  final AtomicInteger createCalls = new AtomicInteger(), queryCalls = new AtomicInteger();
  final List<String> receivedKeys = new CopyOnWriteArrayList<>();
  private final Map<String, RechargeRequest> requests = new ConcurrentHashMap<>();
  private final Map<String, ChannelPayment> payments = new ConcurrentHashMap<>();
  private final ObjectMapper json = new ObjectMapper();
  private final HttpServer server;
  private final ExecutorService workers =
      Executors.newFixedThreadPool(
          4,
          run -> {
            Thread t = new Thread(run, "simulated-payment-channel");
            t.setDaemon(true);
            return t;
          });

  SimulatedPaymentChannel() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    server.setExecutor(workers);
    server.createContext("/payments", this::handle);
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void markPaid(String order) {
    payments.compute(
        order,
        (key, old) -> {
          if (old == null) throw new IllegalStateException("Unknown simulated payment");
          return new ChannelPayment(
              old.merchantOrderId, "PAID", old.reference, old.amount, old.currency);
        });
  }

  void overrideAmount(String order, BigDecimal amount) {
    payments.computeIfPresent(
        order,
        (key, old) ->
            new ChannelPayment(
                old.merchantOrderId, old.state, old.reference, amount, old.currency));
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      if ("POST".equals(exchange.getRequestMethod())) create(exchange);
      else if ("GET".equals(exchange.getRequestMethod())) query(exchange);
      else reply(exchange, 405, new ChannelPayment(null, "METHOD_NOT_ALLOWED", null, null, null));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } catch (Exception ex) {
      try {
        reply(exchange, 500, new ChannelPayment(null, "UNCLASSIFIED", null, null, null));
      } catch (IOException ignored) {
      }
    } finally {
      exchange.close();
    }
  }

  private void create(HttpExchange exchange) throws Exception {
    JsonNode body = json.readTree(exchange.getRequestBody());
    RechargeRequest request = new RechargeRequest();
    request.orderId = body.path("merchantOrderId").asText();
    request.amount = body.get("amount").decimalValue();
    request.currency = body.path("currency").asText();
    lastRequest = request;
    requests.put(request.orderId, request);
    receivedKeys.add(body.path("idempotencyKey").asText());
    int count = createCalls.incrementAndGet();
    Mode selected = mode;
    if (selected == Mode.NOT_ACCEPTED_ONCE && count == 1) {
      reply(exchange, 503, result(request, "NOT_ACCEPTED_RETRYABLE", null));
      return;
    }
    if (selected == Mode.UNCLASSIFIED) {
      reply(exchange, 502, result(request, "UNCLASSIFIED", null));
      return;
    }
    if (selected == Mode.DECLINE) {
      ChannelPayment p = result(request, "DECLINED", null);
      payments.put(request.orderId, p);
      reply(exchange, 200, p);
      return;
    }
    ChannelPayment payment =
        payments.computeIfAbsent(
            request.orderId,
            id ->
                result(
                    request,
                    selected == Mode.IMMEDIATELY_PAID ? "PAID" : "ACCEPTED",
                    "channel-" + request.orderId));
    Notification callback = onAccepted;
    if (callback != null) callback.accepted(request.orderId);
    if (selected == Mode.ACCEPT_THEN_TIMEOUT) Thread.sleep(700);
    reply(exchange, 200, payment);
  }

  private void query(HttpExchange exchange) throws Exception {
    queryCalls.incrementAndGet();
    String encoded = exchange.getRequestURI().getRawQuery();
    String order = URLDecoder.decode(encoded.substring("merchantOrderId=".length()), "UTF-8");
    ChannelPayment found = payments.get(order);
    if (found != null) {
      reply(exchange, 200, found);
      return;
    }
    RechargeRequest request = requests.get(order);
    reply(
        exchange,
        404,
        request == null
            ? new ChannelPayment(order, "NOT_FOUND", null, null, null)
            : result(request, "NOT_FOUND", null));
  }

  private ChannelPayment result(RechargeRequest r, String state, String ref) {
    return new ChannelPayment(r.orderId, state, ref, r.amount, r.currency);
  }

  private void reply(HttpExchange exchange, int status, ChannelPayment payment) throws IOException {
    byte[] bytes = json.writeValueAsBytes(payment);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  public void close() {
    server.stop(0);
    workers.shutdownNow();
    try {
      workers.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
