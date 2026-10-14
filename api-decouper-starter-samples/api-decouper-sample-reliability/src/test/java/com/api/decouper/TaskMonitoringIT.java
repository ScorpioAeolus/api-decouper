package com.api.decouper;

import static org.junit.jupiter.api.Assertions.*;

import com.api.decouper.api.*;
import com.api.decouper.autoconfigure.*;
import com.api.decouper.core.*;
import com.api.decouper.entity.*;
import com.api.decouper.observability.*;
import com.api.decouper.samples.support.MySqlFixture;
import java.util.*;
import org.junit.jupiter.api.Test;

class TaskMonitoringIT extends MySqlFixture {
  @Test
  void expiryMetricsCountOnlyExpiredRowsAndSinkFailureDoesNotAffectTask() {
    for (int i = 0; i < 2; i++) {
      final int index = i;
      long id = tx.execute(s -> repo.insert(new TaskRequest("PAY", 1, "b", "k" + index, "{}", 1)));
      repo.claim(repo.findById(id), 30000);
    }
    jdbc.update("UPDATE local_task SET lease_until=0 WHERE operation_key='k0'");
    DecouperProperties p = new DecouperProperties();
    TaskMonitoringService service =
        new TaskMonitoringService(
            repo,
            new TaskAlertMonitor(
                a -> {
                  throw new IllegalStateException();
                },
                p,
                System::currentTimeMillis),
            p,
            new RuntimeSignals());
    try {
      assertEquals(1L, service.snapshot().get(AlertRule.LEASE_EXPIRED).longValue());
      assertDoesNotThrow(service::evaluate);
    } finally {
      service.close();
    }
  }

  @Test
  void renewedButHungExecutionIsStillVisible() {
    long id = tx.execute(st -> repo.insert(new TaskRequest("PAY", 1, "b", "hung", "{}", 1)));
    LocalTask owned = repo.claim(repo.findById(id), 30000);
    jdbc.update("UPDATE local_task SET update_time=0 WHERE id=?", id);
    repo.renew(owned, 30000);
    DecouperProperties p = new DecouperProperties();
    TaskMonitoringService service =
        new TaskMonitoringService(
            repo,
            new TaskAlertMonitor(a -> {}, p, System::currentTimeMillis),
            p,
            new RuntimeSignals());
    try {
      assertEquals(1L, service.snapshot().get(AlertRule.LONG_RUNNING).longValue());
    } finally {
      service.close();
    }
  }
}
