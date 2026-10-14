package com.api.decouper.examples.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Demonstration protocol for the local simulator. Real channels must supply their own
 * TLS/auth/signing rules.
 */
public final class HttpPaymentChannelClient implements PaymentChannelClient {
  private final String base;
  private final int connectTimeout;
  private final int readTimeout;
  private final ObjectMapper json = new ObjectMapper();

  public HttpPaymentChannelClient(String baseUrl, int connectTimeoutMillis, int readTimeoutMillis) {
    URI uri = URI.create(baseUrl);
    if (!Arrays.asList("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || connectTimeoutMillis < 1
        || readTimeoutMillis < 1)
      throw new IllegalArgumentException("Invalid channel endpoint/timeouts");
    base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    connectTimeout = connectTimeoutMillis;
    readTimeout = readTimeoutMillis;
  }

  public ChannelPayment create(String key, RechargeRequest request) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("idempotencyKey", key);
    body.put("merchantOrderId", request.orderId);
    body.put("amount", request.amount);
    body.put("currency", request.currency);
    return call("/payments", json.writeValueAsBytes(body));
  }

  public ChannelPayment query(String order) throws Exception {
    return call("/payments?merchantOrderId=" + URLEncoder.encode(order, "UTF-8"), null);
  }

  private ChannelPayment call(String path, byte[] body) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
    c.setConnectTimeout(connectTimeout);
    c.setReadTimeout(readTimeout);
    c.setInstanceFollowRedirects(false);
    try {
      if (body != null) {
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = c.getOutputStream()) {
          out.write(body);
        }
      }
      int code = c.getResponseCode();
      InputStream raw = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
      if (raw == null) throw new IOException("Channel returned no classified response");
      ChannelPayment reply;
      try (InputStream in = raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int n;
        while ((n = in.read(buffer)) != -1) {
          if (bytes.size() + n > 65536) throw new IOException("Channel response exceeds limit");
          bytes.write(buffer, 0, n);
        }
        reply = json.readValue(bytes.toByteArray(), ChannelPayment.class);
      }
      // This simulator's protocol explicitly guarantees that this 503 response did not create a
      // payment.
      // An arbitrary 503, timeout or missing response NEVER authorizes replay.
      if (code >= 200 && code < 300
          || (code == 503 && "NOT_ACCEPTED_RETRYABLE".equals(reply.state))) return reply;
      if (code == 404 && "NOT_FOUND".equals(reply.state)) return reply;
      throw new IOException("Unclassified channel HTTP response: " + code);
    } finally {
      c.disconnect();
    }
  }
}
