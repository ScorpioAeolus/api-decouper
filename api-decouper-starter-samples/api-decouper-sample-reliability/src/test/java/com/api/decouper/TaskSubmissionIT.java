package com.api.decouper;

import static org.junit.jupiter.api.Assertions.*;

import com.api.decouper.api.*;
import com.api.decouper.core.*;
import com.api.decouper.samples.support.MySqlFixture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskSubmissionIT extends MySqlFixture {
  TaskRequest request() {
    return new TaskRequest("PAY", 1, "order1", "PAY:order1", "{}", 1);
  }

  @Test
  void mandatoryTransactionAndAtomicRollback() {
    TaskSubmissionService s =
        new TaskSubmissionService(repo, null, new com.api.decouper.observability.RuntimeSignals());
    assertThrows(IllegalStateException.class, () -> s.submit(request()));
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.execute(
                st -> {
                  jdbc.update("INSERT INTO sample_order VALUES('order1','NEW')");
                  s.submit(request());
                  throw new IllegalStateException();
                }));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM local_task", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM local_task_event", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sample_order", Integer.class));
  }

  @Test
  void realIdAfterCommitAndMqFailureDoesNotLoseTask() {
    AtomicInteger sent = new AtomicInteger();
    TaskSubmissionService s =
        new TaskSubmissionService(
            repo,
            id -> {
              sent.incrementAndGet();
              throw new Exception("unavailable");
            },
            new com.api.decouper.observability.RuntimeSignals());
    long id =
        tx.execute(
            st -> {
              long n = s.submit(request());
              assertEquals(0, sent.get());
              return n;
            });
    assertTrue(id > 0);
    assertNotNull(repo.findById(id));
    assertEquals(1, sent.get());
    assertEquals(id, tx.execute(st -> s.submit(request())).longValue());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tx.execute(
                st -> s.submit(new TaskRequest("PAY", 1, "order1", "PAY:order1", "different", 1))));
  }

  @Test
  void foreignDatasourceTransactionRejected() {
    TaskSubmissionService s =
        new TaskSubmissionService(repo, null, new com.api.decouper.observability.RuntimeSignals());
    org.springframework.jdbc.datasource.DriverManagerDataSource other =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            ds.getUrl(), settings.getUsername(), settings.getPassword());
    TransactionTemplateOther.run(
        other, () -> assertThrows(IllegalStateException.class, () -> s.submit(request())));
  }

  static class TransactionTemplateOther {
    static void run(javax.sql.DataSource ds, Runnable r) {
      new org.springframework.transaction.support.TransactionTemplate(
              new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds))
          .execute(
              s -> {
                r.run();
                return null;
              });
    }
  }

  @Test
  void caughtSubmissionConflictStillMarksBusinessTransactionRollbackOnly() {
    TaskSubmissionService service =
        new TaskSubmissionService(repo, null, new com.api.decouper.observability.RuntimeSignals());
    tx.execute(st -> service.submit(request()));
    assertThrows(
        org.springframework.transaction.UnexpectedRollbackException.class,
        () ->
            tx.execute(
                st -> {
                  jdbc.update("INSERT INTO sample_order VALUES('conflict','NEW')");
                  try {
                    service.submit(new TaskRequest("PAY", 1, "different", "PAY:order1", "{}", 1));
                  } catch (IllegalArgumentException ignored) {
                  }
                  return null;
                }));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sample_order", Integer.class));
  }
}
