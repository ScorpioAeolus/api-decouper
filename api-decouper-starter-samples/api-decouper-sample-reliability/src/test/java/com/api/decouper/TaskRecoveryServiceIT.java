package com.api.decouper;

import static org.junit.jupiter.api.Assertions.*;

import com.api.decouper.api.*;
import com.api.decouper.core.*;
import com.api.decouper.entity.*;
import com.api.decouper.samples.support.MySqlFixture;
import org.junit.jupiter.api.Test;

class TaskRecoveryServiceIT extends MySqlFixture {
  @Test
  void manualResumeRequiresVersionReasonAndKeepsResultPhase() {
    long id = tx.execute(s -> repo.insert(new TaskRequest("PAY", 1, "b", "key", "{}", 1)));
    LocalTask t = repo.claim(repo.findById(id), 1000);
    repo.saveResult(t, "accepted", 1, ExecutionResult.Kind.SUCCESS, "CONFIRMED");
    t = repo.claim(repo.findById(id), 1000);
    repo.defer(t, TaskStatus.MANUAL, repo.dbNow(), "BUDGET_EXHAUSTED");
    LocalTask manual = repo.findById(id);
    TaskRecoveryService service = new TaskRecoveryService(repo);
    assertFalse(service.resume(id, manual.getVersion() - 1, "approved"));
    assertThrows(IllegalArgumentException.class, () -> service.resume(id, manual.getVersion(), ""));
    assertTrue(service.resume(id, manual.getVersion(), "verified local recovery"));
    assertEquals(TaskStatus.RESULT_READY, repo.findById(id).getStatus());
    assertEquals("accepted", repo.findById(id).getResultPayload());
    assertFalse(service.resume(id, repo.findById(id).getVersion(), "again"));
  }
}
