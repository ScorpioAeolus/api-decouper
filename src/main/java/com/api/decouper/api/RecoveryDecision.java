package com.api.decouper.api;
import lombok.Value;
import java.time.Instant;
@Value public class RecoveryDecision<R> {
 public enum Kind { RESULT, WAIT, RETRY, MANUAL }
 Kind kind; ExecutionResult<R> result; Instant nextExecuteTime; String reason;
 private RecoveryDecision(Kind k,ExecutionResult<R> r,Instant t,String why){kind=k;result=r;nextExecuteTime=t;reason=Checks.text(why,128,"reason");}
 public static <R> RecoveryDecision<R> confirmed(ExecutionResult<R> r){if(r==null||!r.isConfirmed())throw new IllegalArgumentException("confirmed result required");return new RecoveryDecision<>(Kind.RESULT,r,null,r.getReason());}
 public static <R> RecoveryDecision<R> waitUntil(Instant t,String why){if(t==null)throw new IllegalArgumentException("time required");return new RecoveryDecision<>(Kind.WAIT,null,t,why);}
 public static <R> RecoveryDecision<R> retry(Instant t,String why){if(t==null)throw new IllegalArgumentException("time required");return new RecoveryDecision<>(Kind.RETRY,null,t,why);}
 public static <R> RecoveryDecision<R> manual(String why){return new RecoveryDecision<>(Kind.MANUAL,null,null,why);}
}
