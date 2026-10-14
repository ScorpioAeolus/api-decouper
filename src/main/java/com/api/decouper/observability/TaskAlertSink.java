package com.api.decouper.observability;

@FunctionalInterface
public interface TaskAlertSink {
  void accept(TaskAlert alert);
}
