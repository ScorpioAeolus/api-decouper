package com.api.decouper.observability;

import com.api.decouper.autoconfigure.DecouperProperties;
import java.util.*;
import java.util.function.LongSupplier;

/** Stateful window/cooldown engine. Feed complete snapshots: missing rules mean healthy. */
public final class TaskAlertMonitor {
  private final TaskAlertSink sink;
  private final DecouperProperties config;
  private final LongSupplier clock;
  private final Map<AlertRule, State> states = new EnumMap<>(AlertRule.class);

  private static class State {
    long since, lastSent;
    boolean active;

    State(long now) {
      since = now;
    }
  }

  public TaskAlertMonitor(TaskAlertSink sink, DecouperProperties config, LongSupplier clock) {
    this.sink = sink;
    this.config = config;
    this.clock = clock;
  }

  public synchronized void evaluate(Map<AlertRule, Long> counts) {
    evaluateObserved(counts, java.util.Arrays.asList(AlertRule.values()));
  }

  public synchronized void evaluatePartial(Map<AlertRule, Long> counts) {
    evaluateObserved(counts, counts.keySet());
  }

  private void evaluateObserved(Map<AlertRule, Long> counts, Collection<AlertRule> rules) {
    long now = clock.getAsLong();
    for (AlertRule rule : rules) {
      if (config.getDisabledAlertRules().contains(rule.name())) continue;
      long value = counts.getOrDefault(rule, 0L);
      long threshold =
          config
              .getAlertCountThresholds()
              .getOrDefault(
                  rule.name(),
                  rule == AlertRule.BACKLOG ? (long) config.getBacklogThreshold() : 1L);
      State s = states.get(rule);
      if (value >= threshold) {
        if (s == null) {
          s = new State(now);
          states.put(rule, s);
        }
        if (now - s.since >= config.getAlertWindowMillis()
            && (!s.active || now - s.lastSent >= config.getAlertCooldownMillis())) {
          if (send(new TaskAlert(rule, now, value, false))) {
            s.lastSent = now;
            s.active = true;
          }
        }
      } else if (s != null) {
        if (!s.active || send(new TaskAlert(rule, now, value, true))) states.remove(rule);
      }
    }
  }

  private boolean send(TaskAlert a) {
    try {
      sink.accept(a);
      return true;
    } catch (RuntimeException ex) {
      return false;
    }
  }
}
