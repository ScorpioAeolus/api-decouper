package com.api.decouper.api;

import lombok.Value;

@Value
public class TaskRequest {
  String bizType;
  int handlerVersion;
  String bizId;
  String operationKey;
  String payload;
  int payloadVersion;

  public TaskRequest(String type, int version, String bizId, String key, String payload, int pv) {
    this.bizType = Checks.text(type, 80, "bizType");
    this.handlerVersion = Checks.positive(version, "handlerVersion");
    this.bizId = Checks.text(bizId, 128, "bizId");
    this.operationKey = Checks.text(key, 160, "operationKey");
    this.payload = Checks.text(payload, 65535, "payload");
    this.payloadVersion = Checks.positive(pv, "payloadVersion");
  }
}
