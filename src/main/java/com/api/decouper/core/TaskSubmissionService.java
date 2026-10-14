package com.api.decouper.core;

import com.api.decouper.api.*;
import com.api.decouper.observability.RuntimeSignals;
import com.api.decouper.repository.LocalTaskRepository;
import org.springframework.transaction.support.*;

public class TaskSubmissionService {
  private final LocalTaskRepository repository;
  private final TaskMessagePublisher publisher;
  private final RuntimeSignals signals;

  public TaskSubmissionService(LocalTaskRepository r, TaskMessagePublisher p, RuntimeSignals s) {
    repository = r;
    publisher = p;
    signals = s;
  }

  public long submit(TaskRequest request) {
    return repository.mandatory(
        () -> {
          long id = repository.insert(request);
          if (publisher != null)
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  public void afterCommit() {
                    try {
                      publisher.publish(id);
                      signals.mqSuccess();
                    } catch (Exception e) {
                      signals.mqFailure();
                    }
                  }
                });
          return id;
        });
  }
}
