package com.api.decouper.api;
/** Infrastructure exceptions propagate: the MQ adapter should request redelivery. */
public enum TaskTriggerResult {
  DONE,
  ADVANCED,
  SKIPPED,
  BUSY,
  NOT_FOUND
}
