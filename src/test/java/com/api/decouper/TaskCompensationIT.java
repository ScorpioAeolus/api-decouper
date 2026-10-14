package com.api.decouper;

import static org.junit.jupiter.api.Assertions.*;

import com.api.decouper.api.*;
import com.api.decouper.autoconfigure.*;
import com.api.decouper.core.*;
import com.api.decouper.entity.*;
import com.api.decouper.observability.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TaskCompensationIT extends MySqlFixture {
  @Test
  void pollingCompletesTaskWithoutMqAndSlowTaskDoesNotBlockScan() throws Exception {
    TaskSubmissionService submission =
        new TaskSubmissionService(
            repo,
            id -> {
              throw new Exception("MQ unavailable");
            },
            new RuntimeSignals());
    long id = tx.execute(s -> submission.submit(new TaskRequest("PAY", 1, "b", "op", "{}", 1)));
    TaskExecutionIT.Payment h = new TaskExecutionIT.Payment();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    h.onApply =
        () -> {
          entered.countDown();
          try {
            release.await(3, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
        };
    DecouperProperties p = new DecouperProperties();
    RuntimeSignals signals = new RuntimeSignals();
    try (TaskExecutorService engine = new TaskExecutorService(repo, (t, v, c) -> h, p, signals);
        TaskScheduler scheduler = new TaskScheduler(repo, engine, p, signals)) {
      long begin = System.nanoTime();
      scheduler.scan();
      assertTrue(System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(2));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      release.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (repo.findById(id).getStatus() != TaskStatus.DONE && System.nanoTime() < deadline)
        Thread.yield();
      assertEquals(TaskStatus.DONE, repo.findById(id).getStatus());
      assertEquals(1, h.calls.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void expiredApplyingOnlyRepeatsLocalResult() {
    long id = tx.execute(s -> repo.insert(new TaskRequest("PAY", 1, "b", "op", "{}", 1)));
    LocalTask t = repo.claim(repo.findById(id), 1000);
    repo.saveResult(t, "accepted", 1, ExecutionResult.Kind.SUCCESS, "CONFIRMED");
    repo.claim(repo.findById(id), 1000);
    jdbc.update("UPDATE local_task SET lease_until=0 WHERE id=?", id);
    assertTrue(repo.recoverExpired(repo.findById(id)));
    TaskExecutionIT.Payment h = new TaskExecutionIT.Payment();
    try (TaskExecutorService e =
        new TaskExecutorService(
            repo, (a, b, c) -> h, new DecouperProperties(), new RuntimeSignals())) {
      e.executeTask(id);
    }
    assertEquals(0, h.calls.get());
    assertEquals(0, h.queries.get());
    assertEquals(1, h.applies.get());
    assertEquals(TaskStatus.DONE, repo.findById(id).getStatus());
  }

  @Test
  void singleRoundBudgetIsSharedAcrossBusinessPhases() throws Exception {
    tx.execute(st -> repo.insert(new TaskRequest("PAY", 1, "a", "op1", "{}", 1)));
    long second = tx.execute(st -> repo.insert(new TaskRequest("PAY", 1, "b", "op2", "{}", 1)));
    jdbc.update("UPDATE local_task SET status='UNKNOWN' WHERE id=?", second);
    DecouperProperties p = new DecouperProperties();
    p.setMaxScanTasks(1);
    TaskExecutionIT.Payment h = new TaskExecutionIT.Payment();
    RuntimeSignals signals = new RuntimeSignals();
    try (TaskExecutorService e = new TaskExecutorService(repo, (a, b, c) -> h, p, signals);
        TaskScheduler scheduler = new TaskScheduler(repo, e, p, signals)) {
      scheduler.scan();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (scheduler.inFlightCount() > 0 && System.nanoTime() < deadline) Thread.yield();
      assertEquals(1, h.calls.get() + h.queries.get());
    }
  }

  @Test
  void expiredApplyingLockCannotBlockUnrelatedReadyTaskScan() throws Exception {
    long first = tx.execute(st -> repo.insert(new TaskRequest("PAY", 1, "a", "locked", "{}", 1)));
    DecouperProperties p = new DecouperProperties();
    p.setLeaseMillis(300);
    p.setConcurrency(2);
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1),
        release = new java.util.concurrent.CountDownLatch(1);
    TaskExecutionIT.Payment h =
        new TaskExecutionIT.Payment() {
          public void applyResult(TaskContext c, String input, ExecutionResult<String> result) {
            if (c.getTaskId() == first) {
              entered.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
              }
            }
          }
        };
    RuntimeSignals signals = new RuntimeSignals();
    java.util.concurrent.ExecutorService threads =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    try (TaskExecutorService e = new TaskExecutorService(repo, (a, b, c) -> h, p, signals);
        TaskScheduler scheduler = new TaskScheduler(repo, e, p, signals)) {
      java.util.concurrent.Future<?> original = threads.submit(() -> e.executeTask(first));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      LocalTask applying = repo.findById(first);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (repo.dbNow().toEpochMilli() <= applying.getLeaseUntil()
          && System.nanoTime() < deadline) Thread.yield();
      long second = tx.execute(st -> repo.insert(new TaskRequest("PAY", 1, "b", "ready", "{}", 1)));
      java.util.concurrent.Future<?> scan = threads.submit(scheduler::scan);
      scan.get(1, TimeUnit.SECONDS);
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (repo.findById(second).getStatus() != TaskStatus.DONE && System.nanoTime() < deadline)
        Thread.yield();
      assertEquals(TaskStatus.DONE, repo.findById(second).getStatus());
      release.countDown();
      original.get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      threads.shutdownNow();
    }
  }

  @Test
  void configuredPeriodicPollingPicksUpTasksCreatedAfterInitialScan() throws Exception {
    DecouperProperties p = new DecouperProperties();
    p.setPollIntervalMillis(30);
    RuntimeSignals signals = new RuntimeSignals();
    signals.lastScanSuccess.set(0);
    TaskExecutionIT.Payment h = new TaskExecutionIT.Payment();
    try (TaskExecutorService e = new TaskExecutorService(repo, (a, b, c) -> h, p, signals);
        TaskScheduler scheduler = new TaskScheduler(repo, e, p, signals)) {
      scheduler.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (signals.lastScanSuccess.get() == 0 && System.nanoTime() < deadline) Thread.yield();
      assertTrue(signals.lastScanSuccess.get() > 0);
      long id = tx.execute(st -> repo.insert(new TaskRequest("PAY", 1, "b", "periodic", "{}", 1)));
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (repo.findById(id).getStatus() != TaskStatus.DONE && System.nanoTime() < deadline)
        Thread.yield();
      assertEquals(TaskStatus.DONE, repo.findById(id).getStatus());
      assertEquals(1, h.calls.get());
    }
  }
}
