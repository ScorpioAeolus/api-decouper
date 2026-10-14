package com.api.decouper.core;

import com.api.decouper.autoconfigure.*;
import com.api.decouper.entity.*;
import com.api.decouper.observability.*;
import com.api.decouper.repository.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Multi-instance polling: every node scans, only a fenced CAS winner executes. */
public final class TaskScheduler implements AutoCloseable {
  private final LocalTaskRepository repository;
  private final TaskExecutorService engine;
  private final DecouperProperties config;
  private final RuntimeSignals signals;
  private final ThreadPoolExecutor workers;
  private final ThreadPoolExecutor reclaimers;
  private final Set<Long> reclaiming = ConcurrentHashMap.newKeySet();
  private final ScheduledExecutorService ticker;
  private final ReentrantLock scanLock = new ReentrantLock();
  private int stateOffset;
  private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean();

  public TaskScheduler(
      LocalTaskRepository r, TaskExecutorService e, DecouperProperties p, RuntimeSignals s) {
    repository = r;
    engine = e;
    config = p;
    signals = s;
    workers =
        new ThreadPoolExecutor(
            p.getConcurrency(),
            p.getConcurrency(),
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(p.getConcurrency()),
            run -> {
              Thread t = new Thread(run, "decouper-worker");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    reclaimers =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(p.getBatchSize()),
            run -> {
              Thread thread = new Thread(run, "decouper-reclaim");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    ticker =
        Executors.newSingleThreadScheduledExecutor(
            run -> {
              Thread t = new Thread(run, "decouper-scan");
              t.setDaemon(true);
              return t;
            });
  }

  public int inFlightCount() {
    return workers.getActiveCount() + workers.getQueue().size();
  }

  public void start() {
    if (config.isSchedulingEnabled() && !closed.get() && started.compareAndSet(false, true))
      ticker.scheduleWithFixedDelay(
          () -> {
            try {
              scan();
            } catch (RuntimeException ignored) {
              /* lastScanSuccess drives the stalled-scan alert. */
            }
          },
          0,
          config.getPollIntervalMillis(),
          TimeUnit.MILLISECONDS);
  }

  public void scan() {
    if (closed.get() || !scanLock.tryLock()) return;
    try {
      long deadline =
          System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getScanBudgetMillis());
      int remaining = config.getMaxScanTasks();
      for (LocalTask task : repository.findExpired(Math.min(config.getBatchSize(), remaining))) {
        if (remaining == 0 || System.nanoTime() >= deadline || closed.get()) break;
        if (!reclaiming.add(task.getId())) continue;
        try {
          reclaimers.execute(
              () -> {
                try {
                  repository.recoverExpired(task);
                } catch (RuntimeException ex) {
                  signals.workerFailureSince.compareAndSet(0, System.currentTimeMillis());
                } finally {
                  reclaiming.remove(task.getId());
                }
              });
          remaining--;
        } catch (RejectedExecutionException ex) {
          reclaiming.remove(task.getId());
          break;
        }
      }
      int perState = Math.max(1, config.getMaxScanTasks() / 4);
      List<TaskStatus> phases =
          Arrays.asList(
              TaskStatus.RESULT_READY, TaskStatus.UNKNOWN, TaskStatus.READY, TaskStatus.RETRY_WAIT);
      int start = stateOffset++ % phases.size();
      for (int phase = 0; phase < phases.size() && remaining > 0; phase++) {
        TaskStatus state = phases.get((start + phase) % phases.size());
        long cursor = 0, id = 0;
        int count = 0;
        while (count < perState && remaining > 0 && System.nanoTime() < deadline && !closed.get()) {
          List<LocalTask> page =
              repository.findDue(
                  state,
                  cursor,
                  id,
                  Math.min(remaining, Math.min(config.getBatchSize(), perState - count)));
          if (page.isEmpty()) break;
          for (LocalTask t : page) {
            try {
              workers.execute(
                  () -> {
                    try {
                      engine.executeTask(t.getId());
                      signals.workerFailureSince.set(0);
                    } catch (RuntimeException ex) {
                      signals.workerFailureSince.compareAndSet(0, System.currentTimeMillis());
                    }
                  });
            } catch (RejectedExecutionException ex) {
              break;
            }
          }
          LocalTask last = page.get(page.size() - 1);
          cursor = last.getNextExecuteTime();
          id = last.getId();
          count += page.size();
          remaining -= page.size();
        }
      }
      signals.lastScanSuccess.set(System.currentTimeMillis());
    } finally {
      scanLock.unlock();
    }
  }

  public void close() {
    if (closed.compareAndSet(false, true)) {
      ticker.shutdownNow();
      workers.shutdown();
      reclaimers.shutdown();
      try {
        if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow();
        if (!reclaimers.awaitTermination(10, TimeUnit.SECONDS)) reclaimers.shutdownNow();
      } catch (InterruptedException ex) {
        workers.shutdownNow();
        reclaimers.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }
}
