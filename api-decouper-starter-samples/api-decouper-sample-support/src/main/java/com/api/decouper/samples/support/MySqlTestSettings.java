package com.api.decouper.samples.support;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/** Explicit connection settings for destructive fixtures in a user-provided dedicated database. */
public final class MySqlTestSettings {
  private final String url;
  private final String username;
  private final String password;

  private MySqlTestSettings(String url, String username, String password) {
    this.url = url;
    this.username = username;
    this.password = password;
  }

  public static MySqlTestSettings from(Map<String, String> environment) {
    String url = required(environment, "DECOUPER_TEST_JDBC_URL");
    String username = required(environment, "DECOUPER_TEST_USERNAME");
    String password = required(environment, "DECOUPER_TEST_PASSWORD");
    if (!"true".equals(environment.get("DECOUPER_TEST_ALLOW_RESET"))) {
      throw new IllegalStateException(
          "Set DECOUPER_TEST_ALLOW_RESET=true only for the dedicated test database; fixtures rebuild test tables");
    }
    if (!url.startsWith("jdbc:mysql://"))
      throw new IllegalArgumentException("A MySQL test JDBC URL is required");
    URI uri;
    try {
      uri = URI.create(url.substring(5));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid MySQL test URL");
    }
    String path = uri.getPath();
    String schema = path == null ? "" : path.substring(Math.min(1, path.length()));
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || !schema.matches("[A-Za-z0-9_]+")
        || Arrays.asList("mysql", "sys", "information_schema", "performance_schema")
            .contains(schema.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException(
          "Use a dedicated non-system test schema and separate credentials");
    }
    return new MySqlTestSettings(url, username, password);
  }

  private static String required(Map<String, String> values, String key) {
    String value = values.get(key);
    if (value == null || value.trim().isEmpty())
      throw new IllegalStateException("Missing test environment variable: " + key);
    return value;
  }

  public String getUrl() {
    return url;
  }

  public String getUsername() {
    return username;
  }

  public String getPassword() {
    return password;
  }
}
