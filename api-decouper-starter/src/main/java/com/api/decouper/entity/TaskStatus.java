package com.api.decouper.entity;

public enum TaskStatus {
  READY,
  EXECUTING,
  RETRY_WAIT,
  UNKNOWN,
  RECOVERING,
  RESULT_READY,
  APPLYING,
  DONE,
  MANUAL;

  public boolean runnable() {
    return this == READY || this == RETRY_WAIT || this == UNKNOWN || this == RESULT_READY;
  }

  public boolean running() {
    return this == EXECUTING || this == RECOVERING || this == APPLYING;
  }

  public TaskStatus runningPhase() {
    switch (this) {
      case READY:
      case RETRY_WAIT:
        return EXECUTING;
      case UNKNOWN:
        return RECOVERING;
      case RESULT_READY:
        return APPLYING;
      default:
        throw new IllegalStateException("Not runnable");
    }
  }

  public boolean canFinishAs(TaskStatus to) {
    if (!running()) return false;
    if (to == MANUAL) return true;
    if (this == APPLYING) return to == DONE || to == RESULT_READY;
    return to == RESULT_READY || to == UNKNOWN || to == RETRY_WAIT;
  }
}
