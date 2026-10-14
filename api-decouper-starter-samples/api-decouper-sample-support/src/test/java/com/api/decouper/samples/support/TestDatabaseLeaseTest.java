package com.api.decouper.samples.support;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TestDatabaseLeaseTest {
  @Test
  void sameDatabaseCannotBeResetByTwoCasesAtOnce() throws Exception {
    String url =
        "jdbc:mysql://127.0.0.1:3306/test_" + UUID.randomUUID().toString().replace("-", "");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (TestDatabaseLease first = TestDatabaseLease.acquire(url)) {
      Future<Boolean> second =
          executor.submit(
              () -> {
                try (TestDatabaseLease lease = TestDatabaseLease.acquire(url)) {
                  return true;
                }
              });
      assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
      first.close();
      assertTrue(second.get(2, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }
}
