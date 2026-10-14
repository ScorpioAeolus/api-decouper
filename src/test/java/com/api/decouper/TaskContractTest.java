package com.api.decouper;
import com.api.decouper.api.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class TaskContractTest {
 @Test void retryRequiresTimeAndReason() {
  assertThrows(IllegalArgumentException.class, () -> ExecutionResult.retry(null,"safe"));
  assertThrows(IllegalArgumentException.class, () -> ExecutionResult.retry(Instant.now(),""));
  assertEquals(ExecutionResult.Kind.RETRY, ExecutionResult.retry(Instant.now(),"idempotent").getKind());
 }
 @Test void confirmedFailureIsDurableBusinessResult() {
  ExecutionResult<String> r=ExecutionResult.failure("DECLINED","ref");
  assertTrue(r.isConfirmed()); assertEquals("ref",r.getValue());
  assertFalse(ExecutionResult.unknown("timeout").isConfirmed());
  assertEquals(RecoveryDecision.Kind.MANUAL, RecoveryDecision.manual("no query").getKind());
 }
 @Test void requestRejectsUnversionedOrBlankRouting() {
  assertThrows(IllegalArgumentException.class, () -> new TaskRequest("",1,"b","op","{}",1));
  assertThrows(IllegalArgumentException.class, () -> new TaskRequest("PAY",0,"b","op","{}",1));
 }
}
