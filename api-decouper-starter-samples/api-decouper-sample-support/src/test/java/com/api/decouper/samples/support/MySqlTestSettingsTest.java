package com.api.decouper.samples.support;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MySqlTestSettingsTest {
  private Map<String, String> configured() {
    Map<String, String> values = new HashMap<>();
    values.put(
        "DECOUPER_TEST_JDBC_URL",
        "jdbc:mysql://127.0.0.1:3306/api_decouper_test?serverTimezone=UTC");
    values.put("DECOUPER_TEST_USERNAME", "decouper_test");
    values.put("DECOUPER_TEST_PASSWORD", "fixture-secret");
    values.put("DECOUPER_TEST_ALLOW_RESET", "true");
    return values;
  }

  @Test
  void noImplicitRootCredentialsOrDefaultDatabase() {
    assertThrows(IllegalStateException.class, () -> MySqlTestSettings.from(Collections.emptyMap()));
    Map<String, String> values = configured();
    values.remove("DECOUPER_TEST_PASSWORD");
    assertThrows(IllegalStateException.class, () -> MySqlTestSettings.from(values));
  }

  @Test
  void destructiveFixturesRequireExplicitOptIn() {
    Map<String, String> values = configured();
    values.remove("DECOUPER_TEST_ALLOW_RESET");
    assertThrows(IllegalStateException.class, () -> MySqlTestSettings.from(values));
  }

  @Test
  void systemSchemasAndMissingSchemaAreRejected() {
    for (String db :
        Arrays.asList("mysql", "sys", "information_schema", "performance_schema", "")) {
      Map<String, String> values = configured();
      values.put("DECOUPER_TEST_JDBC_URL", "jdbc:mysql://127.0.0.1:3306/" + db);
      assertThrows(IllegalArgumentException.class, () -> MySqlTestSettings.from(values));
    }
  }

  @Test
  void suppliedDedicatedAccountIsUsedWithoutExposingPassword() {
    MySqlTestSettings config = MySqlTestSettings.from(configured());
    assertEquals("decouper_test", config.getUsername());
    assertEquals("fixture-secret", config.getPassword());
    assertFalse(config.toString().contains("fixture-secret"));
  }
}
