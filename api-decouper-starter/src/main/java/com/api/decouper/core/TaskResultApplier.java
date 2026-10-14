package com.api.decouper.core;

import com.api.decouper.api.*;
import com.api.decouper.entity.*;
import com.api.decouper.repository.LocalTaskRepository;
import com.api.decouper.spi.*;

public final class TaskResultApplier {
  private final LocalTaskRepository repository;

  public TaskResultApplier(LocalTaskRepository r) {
    repository = r;
  }

  public <P, R> void apply(LocalTask owned, TaskHandler<P, R> handler) {
    repository.transaction(
        () -> {
          LocalTask t = repository.lockOwned(owned);
          TaskCodec<P, R> codec = handler.codec();
          P request = codec.decodeRequest(t.getPayload(), t.getPayloadVersion());
          R value = codec.decodeResult(t.getResultPayload(), t.getResultVersion());
          ExecutionResult<R> result =
              "SUCCESS".equals(t.getResultOutcome())
                  ? ExecutionResult.success(value)
                  : ExecutionResult.failure(t.getResultReason(), value);
          try {
            handler.applyResult(t.context(), request, result);
          } catch (Exception e) {
            throw new ApplyException(e);
          }
          repository.completeApplied(t);
          return null;
        });
  }

  private static class ApplyException extends RuntimeException {
    ApplyException(Exception e) {
      super(e);
    }
  }
}
