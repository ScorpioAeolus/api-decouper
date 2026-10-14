package com.api.decouper;

import static org.junit.jupiter.api.Assertions.*;

import com.api.decouper.autoconfigure.*;
import com.api.decouper.observability.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TaskAlertMonitorTest {
  @Test
  void rulesAggregateDeduplicateAndSendRecoveryAfterWindow() {
    AtomicLong clock = new AtomicLong(100);
    List<TaskAlert> out = new ArrayList<>();
    DecouperProperties p = new DecouperProperties();
    p.setAlertWindowMillis(10);
    p.setAlertCooldownMillis(100);
    TaskAlertMonitor monitor = new TaskAlertMonitor(out::add, p, clock::get);
    Map<AlertRule, Long> active = Collections.singletonMap(AlertRule.BACKLOG, 1001L);
    monitor.evaluate(active);
    assertTrue(out.isEmpty());
    clock.set(111);
    monitor.evaluate(active);
    assertEquals(1, out.size());
    assertFalse(out.get(0).isRecovered());
    clock.set(150);
    monitor.evaluate(active);
    assertEquals(1, out.size());
    monitor.evaluate(Collections.emptyMap());
    assertEquals(2, out.size());
    assertTrue(out.get(1).isRecovered());
  }

  @Test
  void failingSinkDoesNotBreakStateAndRulesCanBeDisabled() {
    DecouperProperties p = new DecouperProperties();
    p.setAlertWindowMillis(0);
    p.getDisabledAlertRules().add("ROUTE_ERROR");
    List<TaskAlert> out = new ArrayList<>();
    TaskAlertMonitor m =
        new TaskAlertMonitor(
            a -> {
              out.add(a);
              throw new IllegalStateException();
            },
            p,
            () -> 100L);
    assertDoesNotThrow(() -> m.evaluate(Collections.singletonMap(AlertRule.ROUTE_ERROR, 1L)));
    assertEquals(0, out.size());
    assertDoesNotThrow(() -> m.evaluate(Collections.singletonMap(AlertRule.MANUAL, 1L)));
    assertEquals(1, out.size());
  }

  @Test
  void partialObservationCannotRecoverUnobservedDatabaseRules() {
    DecouperProperties p = new DecouperProperties();
    p.setAlertWindowMillis(0);
    java.util.List<TaskAlert> events = new java.util.ArrayList<>();
    TaskAlertMonitor monitor = new TaskAlertMonitor(events::add, p, () -> 100L);
    monitor.evaluate(java.util.Collections.singletonMap(AlertRule.MANUAL, 1L));
    monitor.evaluatePartial(java.util.Collections.singletonMap(AlertRule.SCAN_STALLED, 1L));
    assertEquals(2, events.size());
    assertTrue(events.stream().noneMatch(TaskAlert::isRecovered));
    monitor.evaluate(java.util.Collections.emptyMap());
    assertEquals(2, events.stream().filter(TaskAlert::isRecovered).count());
  }
}
