package com.api.decouper.entity;

import com.api.decouper.api.TaskContext;
import lombok.Data;

@Data
public class LocalTask {
  private long id;
  private String bizType;
  private int handlerVersion;
  private String bizId;
  private String operationKey;
  private String payload;
  private int payloadVersion;
  private TaskStatus status;
  private String resultPayload;
  private int resultVersion;
  private String resultOutcome;
  private String resultReason;
  private String executionToken;
  private Long leaseUntil;
  private int version;
  private int executeAttempts;
  private int recoverAttempts;
  private int applyAttempts;
  private long nextExecuteTime;
  private String lastErrorCode;
  private long createTime;
  private long updateTime;
  private Long completedTime;

  public TaskContext context() {
    return new TaskContext(
        id,
        bizType,
        handlerVersion,
        bizId,
        operationKey,
        payloadVersion,
        executeAttempts,
        recoverAttempts,
        applyAttempts);
  }

  public int phaseAttempts() {
    switch (status) {
      case EXECUTING:
        return executeAttempts;
      case RECOVERING:
        return recoverAttempts;
      case APPLYING:
        return applyAttempts;
      default:
        return 0;
    }
  }
}
