package com.api.decouper.observability;

import com.api.decouper.autoconfigure.DecouperProperties;
import com.api.decouper.repository.LocalTaskRepository;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Polls durable task health separately from the executor and scanner. */
public final class TaskMonitoringService implements AutoCloseable {
  private final LocalTaskRepository repository;
  private final TaskAlertMonitor monitor;
  private final DecouperProperties config;
  private final RuntimeSignals signals;
  private final ScheduledExecutorService timer;
  private final AtomicBoolean started = new AtomicBoolean();

  public TaskMonitoringService(
      LocalTaskRepository r, TaskAlertMonitor m, DecouperProperties p, RuntimeSignals s) {
    repository = r;
    monitor = m;
    config = p;
    signals = s;
    timer =
        Executors.newSingleThreadScheduledExecutor(
            run -> {
              Thread t = new Thread(run, "decouper-alert");
              t.setDaemon(true);
              return t;
            });
  }

  public void start() {
    if (config.isSchedulingEnabled() && started.compareAndSet(false, true))
      timer.scheduleWithFixedDelay(
          () -> {
            try {
              evaluate();
            } catch (RuntimeException ex) {
              Map<AlertRule, Long> v = new EnumMap<>(AlertRule.class);
              v.put(AlertRule.SCAN_STALLED, 1L);
              monitor.evaluatePartial(v);
            }
          },
          config.getPollIntervalMillis(),
          config.getPollIntervalMillis(),
          TimeUnit.MILLISECONDS);
  }

  public Map<AlertRule, Long> snapshot() {
    long dbNow = repository.dbNow().toEpochMilli(), old = dbNow - config.getAlertThresholdMillis();
    Map<AlertRule, Long> counts = new EnumMap<>(AlertRule.class);
    String sql =
        "SELECT SUM(status IN ('READY','RETRY_WAIT','RESULT_READY','UNKNOWN')) AS backlog, "
            + "SUM(status IN ('READY','RETRY_WAIT') AND create_time<?) AS pending, "
            + "SUM(status='UNKNOWN' AND create_time<?) AS unknown_age, "
            + "SUM(status IN ('EXECUTING','RECOVERING','APPLYING') AND lease_until<=?) AS expired, "
            + "SUM(status IN ('EXECUTING','RECOVERING','APPLYING') AND update_time<?) AS long_running, "
            + "SUM(last_error_code='APPLY_FAILED') AS apply_failures, SUM(status='MANUAL') AS manual_count, "
            + "SUM(last_error_code='BUDGET_EXHAUSTED') AS budget, "
            + "SUM(last_error_code IN ('ROUTE_UNAVAILABLE','INPUT_CODEC_ERROR','RESULT_CODEC_ERROR')) AS route_errors "
            + "FROM local_task WHERE status<>'DONE'";
    Map<String, Object> row = repository.jdbc().queryForMap(sql, old, old, dbNow, old);
    put(counts, AlertRule.BACKLOG, row.get("backlog"));
    put(counts, AlertRule.OLD_PENDING, row.get("pending"));
    put(counts, AlertRule.UNKNOWN_AGE, row.get("unknown_age"));
    put(counts, AlertRule.LEASE_EXPIRED, row.get("expired"));
    put(counts, AlertRule.LONG_RUNNING, row.get("long_running"));
    put(counts, AlertRule.APPLY_FAILURES, row.get("apply_failures"));
    put(counts, AlertRule.MANUAL, row.get("manual_count"));
    put(counts, AlertRule.BUDGET_EXHAUSTED, row.get("budget"));
    put(counts, AlertRule.ROUTE_ERROR, row.get("route_errors"));
    long now = System.currentTimeMillis();
    if (signals.mqFailureSince.get() > 0
        && now - signals.mqFailureSince.get() >= config.getAlertThresholdMillis())
      counts.put(AlertRule.MQ_UNAVAILABLE, 1L);
    if (now - signals.lastScanSuccess.get() >= config.getAlertThresholdMillis())
      counts.put(AlertRule.SCAN_STALLED, 1L);
    if (signals.workerFailureSince.get() > 0
        && now - signals.workerFailureSince.get() >= config.getAlertThresholdMillis())
      counts.put(AlertRule.WORKER_FAILURE, 1L);
    return Collections.unmodifiableMap(counts);
  }

  private void put(Map<AlertRule, Long> values, AlertRule rule, Object count) {
    values.put(rule, count == null ? 0L : ((Number) count).longValue());
  }

  public void evaluate() {
    monitor.evaluate(snapshot());
  }

  public void close() {
    timer.shutdownNow();
  }
}
