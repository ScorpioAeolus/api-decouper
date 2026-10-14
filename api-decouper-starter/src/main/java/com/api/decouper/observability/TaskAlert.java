package com.api.decouper.observability;

import lombok.Value;

@Value
public class TaskAlert {
  AlertRule rule;
  long observedAt;
  long count;
  boolean recovered;
}
