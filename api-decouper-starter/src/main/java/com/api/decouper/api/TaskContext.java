package com.api.decouper.api;

import lombok.Value;

@Value
public class TaskContext {
  long taskId;
  String bizType;
  int handlerVersion;
  String bizId;
  String operationKey;
  int payloadVersion;
  int executeAttempts;
  int recoverAttempts;
  int applyAttempts;
}
