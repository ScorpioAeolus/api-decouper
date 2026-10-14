package com.api.decouper.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("api-decouper")
public class DecouperProperties {
  private java.util.Set<String> disabledAlertRules = new java.util.HashSet<>();
  private java.util.Map<String, Long> alertCountThresholds = new java.util.HashMap<>();
  private boolean enabled = false;
  private String dataSourceBean = "dataSource";
  private String transactionManagerBean = "transactionManager";
  private long pollIntervalMillis = 5000,
      leaseMillis = 30000,
      recoveryDelayMillis = 5000,
      backoffMillis = 1000,
      maxBackoffMillis = 60000,
      maxTaskAgeMillis = 86400000;
  private int concurrency = 8,
      batchSize = 100,
      maxScanTasks = 1000,
      maxExecuteAttempts = 5,
      maxRecoverAttempts = 20,
      maxApplyAttempts = 20;
  private long scanBudgetMillis = 1000,
      alertThresholdMillis = 60000,
      alertCooldownMillis = 300000,
      alertWindowMillis = 10000;
  private int backlogThreshold = 1000;
  private boolean schedulingEnabled = true;

  public void validate() {
    for (String name : disabledAlertRules) com.api.decouper.observability.AlertRule.valueOf(name);
    for (java.util.Map.Entry<String, Long> e : alertCountThresholds.entrySet()) {
      com.api.decouper.observability.AlertRule.valueOf(e.getKey());
      if (e.getValue() == null || e.getValue() < 1)
        throw new IllegalArgumentException("Invalid alert threshold");
    }
    if (pollIntervalMillis < 1
        || leaseMillis < 300
        || recoveryDelayMillis < 1
        || backoffMillis < 1
        || maxBackoffMillis < backoffMillis
        || maxTaskAgeMillis < 1
        || concurrency < 1
        || batchSize < 1
        || maxScanTasks < 1
        || maxExecuteAttempts < 1
        || maxRecoverAttempts < 1
        || maxApplyAttempts < 1
        || scanBudgetMillis < 1
        || alertThresholdMillis < 1
        || alertCooldownMillis < 1
        || alertWindowMillis < 0
        || backlogThreshold < 1)
      throw new IllegalArgumentException("Invalid api-decouper configuration");
  }
}
