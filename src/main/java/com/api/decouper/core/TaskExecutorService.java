package com.api.decouper.core;

import com.api.decouper.api.*;
import com.api.decouper.autoconfigure.DecouperProperties;
import com.api.decouper.entity.*;
import com.api.decouper.observability.RuntimeSignals;
import com.api.decouper.repository.*;
import com.api.decouper.spi.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Shared synchronous entry for MQ and scheduler workers. Infrastructure failures propagate for
 * redelivery.
 */
public class TaskExecutorService implements AutoCloseable {
  private final LocalTaskRepository repository;
  private final HandlerResolver resolver;
  private final DecouperProperties config;
  private final RuntimeSignals signals;
  private final Semaphore capacity;
  private final ScheduledExecutorService heartbeats;
  private final TaskResultApplier applier;
  private final AtomicBoolean closed = new AtomicBoolean();

  public TaskExecutorService(
      LocalTaskRepository r, HandlerResolver h, DecouperProperties p, RuntimeSignals s) {
    p.validate();
    repository = r;
    resolver = h;
    config = p;
    signals = s;
    capacity = new Semaphore(p.getConcurrency());
    applier = new TaskResultApplier(r);
    heartbeats =
        Executors.newScheduledThreadPool(
            Math.min(2, p.getConcurrency()),
            run -> {
              Thread t = new Thread(run, "decouper-lease");
              t.setDaemon(true);
              return t;
            });
  }

  public TaskTriggerResult executeTask(long taskId) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Trigger must run outside business transaction");
    if (closed.get() || !capacity.tryAcquire()) return TaskTriggerResult.BUSY;
    try {
      return run(taskId);
    } finally {
      capacity.release();
    }
  }

  private TaskTriggerResult run(long id) {
    LocalTask current = repository.findById(id);
    if (current == null) return TaskTriggerResult.NOT_FOUND;
    if (current.getStatus() == TaskStatus.DONE) return TaskTriggerResult.DONE;
    LocalTask owned = repository.claim(current, config.getLeaseMillis());
    if (owned == null) {
      signals.casMisses.incrementAndGet();
      return TaskTriggerResult.SKIPPED;
    }
    ScheduledFuture<?> heartbeat =
        heartbeats.scheduleWithFixedDelay(
            () -> {
              try {
                repository.renew(owned, config.getLeaseMillis());
              } catch (RuntimeException ignored) {
                /* Lease expiry is observable and recoverable. */
              }
            },
            config.getLeaseMillis() / 3,
            config.getLeaseMillis() / 3,
            TimeUnit.MILLISECONDS);
    try {
      if (exhausted(owned)) {
        repository.defer(owned, TaskStatus.MANUAL, repository.dbNow(), "BUDGET_EXHAUSTED");
        return TaskTriggerResult.ADVANCED;
      }
      TaskHandler<?, ?> handler;
      try {
        handler = resolver.resolve(owned.getBizType(), owned.getHandlerVersion(), owned.context());
      } catch (RuntimeException e) {
        repository.defer(owned, TaskStatus.MANUAL, repository.dbNow(), "ROUTE_UNAVAILABLE");
        return TaskTriggerResult.ADVANCED;
      }
      if (owned.getStatus() == TaskStatus.APPLYING) {
        apply(owned, handler);
        return state(id);
      }
      invoke(owned, handler);
    } finally {
      heartbeat.cancel(false);
    }
    // A separate fenced claim is required for applying the durable result.
    if (repository.findById(id).getStatus() == TaskStatus.RESULT_READY) {
      LocalTask applying = repository.claim(repository.findById(id), config.getLeaseMillis());
      if (applying != null) {
        if (exhausted(applying))
          repository.defer(applying, TaskStatus.MANUAL, repository.dbNow(), "BUDGET_EXHAUSTED");
        else {
          TaskHandler<?, ?> h;
          try {
            h =
                resolver.resolve(
                    applying.getBizType(), applying.getHandlerVersion(), applying.context());
          } catch (RuntimeException ex) {
            repository.defer(applying, TaskStatus.MANUAL, repository.dbNow(), "ROUTE_UNAVAILABLE");
            return state(id);
          }
          apply(applying, h);
        }
      }
    }
    return state(id);
  }

  private boolean exhausted(LocalTask t) {
    int max =
        t.getStatus() == TaskStatus.EXECUTING
            ? config.getMaxExecuteAttempts()
            : t.getStatus() == TaskStatus.RECOVERING
                ? config.getMaxRecoverAttempts()
                : config.getMaxApplyAttempts();
    return t.phaseAttempts() > max
        || (t.getStatus() == TaskStatus.EXECUTING
            && repository.dbNow().toEpochMilli() - t.getCreateTime()
                > config.getMaxTaskAgeMillis());
  }

  private <P, R> void invoke(LocalTask t, TaskHandler<P, R> h) {
    P input;
    TaskCodec<P, R> codec;
    try {
      codec = h.codec();
      input = codec.decodeRequest(t.getPayload(), t.getPayloadVersion());
    } catch (RuntimeException ex) {
      repository.defer(t, TaskStatus.MANUAL, repository.dbNow(), "INPUT_CODEC_ERROR");
      return;
    }
    // Only the business invocation is caught. Persistence failures leave the lease for safe
    // recovery.
    ExecutionResult<R> result = null;
    RecoveryDecision<R> recovery = null;
    try {
      if (t.getStatus() == TaskStatus.EXECUTING) {
        result = h.execute(t.context(), input);
        if (result == null) throw new IllegalStateException("null execution result");
      } else {
        recovery = h.recover(t.context(), input);
        if (recovery == null) throw new IllegalStateException("null recovery decision");
      }
    } catch (Exception e) {
      repository.defer(
          t,
          TaskStatus.UNKNOWN,
          repository.dbNow().plusMillis(config.getRecoveryDelayMillis()),
          "BUSINESS_EXCEPTION");
      return;
    }
    if (recovery != null) {
      switch (recovery.getKind()) {
        case MANUAL:
          repository.defer(t, TaskStatus.MANUAL, repository.dbNow(), recovery.getReason());
          return;
        case WAIT:
          repository.defer(
              t, TaskStatus.UNKNOWN, recovery.getNextExecuteTime(), recovery.getReason());
          return;
        case RETRY:
          repository.defer(
              t, TaskStatus.RETRY_WAIT, recovery.getNextExecuteTime(), recovery.getReason());
          return;
        case RESULT:
          result = recovery.getResult();
          break;
        default:
          throw new IllegalStateException("Unsupported recovery decision");
      }
    }
    if (result.isConfirmed()) {
      String encoded;
      int resultVersion;
      try {
        encoded = codec.encodeResult(result.getValue());
        resultVersion = Checks.positive(codec.resultVersion(), "resultVersion");
        if (encoded != null && encoded.length() > 65535)
          throw new IllegalArgumentException("Result too large");
      } catch (RuntimeException e) {
        repository.defer(
            t,
            TaskStatus.UNKNOWN,
            repository.dbNow().plusMillis(config.getRecoveryDelayMillis()),
            "RESULT_CODEC_ERROR");
        return;
      }
      repository.saveResult(t, encoded, resultVersion, result.getKind(), result.getReason());
    } else if (result.getKind() == ExecutionResult.Kind.RETRY)
      repository.defer(t, TaskStatus.RETRY_WAIT, result.getNextExecuteTime(), result.getReason());
    else
      repository.defer(
          t,
          TaskStatus.UNKNOWN,
          repository.dbNow().plusMillis(config.getRecoveryDelayMillis()),
          result.getReason());
  }

  private void apply(LocalTask t, TaskHandler<?, ?> handler) {
    try {
      applier.apply(t, handler);
    } catch (RuntimeException e) {
      long base = config.getBackoffMillis();
      int shift = Math.min(20, Math.max(0, t.getApplyAttempts() - 1));
      long upper =
          Math.min(
              config.getMaxBackoffMillis(),
              base > (Long.MAX_VALUE >> shift) ? Long.MAX_VALUE : base << shift);
      long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, upper / 4));
      repository.defer(
          t,
          TaskStatus.RESULT_READY,
          repository.dbNow().plusMillis(upper + jitter),
          "APPLY_FAILED");
    }
  }

  private TaskTriggerResult state(long id) {
    return repository.findById(id).getStatus() == TaskStatus.DONE
        ? TaskTriggerResult.DONE
        : TaskTriggerResult.ADVANCED;
  }

  public void close() {
    if (closed.compareAndSet(false, true)) heartbeats.shutdownNow();
  }
}
