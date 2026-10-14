package com.api.decouper.api;
import lombok.Value;
import java.time.Instant;
@Value public class ExecutionResult<R> {
 public enum Kind { SUCCESS, FAILURE, RETRY, UNKNOWN }
 Kind kind; R value; String reason; Instant nextExecuteTime;
 private ExecutionResult(Kind k,R v,String r,Instant n){kind=k;value=v;reason=r;nextExecuteTime=n;}
 public static <R> ExecutionResult<R> success(R v){return new ExecutionResult<>(Kind.SUCCESS,v,"CONFIRMED",null);}
 public static <R> ExecutionResult<R> failure(String reason,R v){return new ExecutionResult<>(Kind.FAILURE,v,Checks.text(reason,128,"reason"),null);}
 public static <R> ExecutionResult<R> retry(Instant at,String reason){if(at==null)throw new IllegalArgumentException("retry time required");return new ExecutionResult<>(Kind.RETRY,null,Checks.text(reason,128,"reason"),at);}
 public static <R> ExecutionResult<R> unknown(String reason){return new ExecutionResult<>(Kind.UNKNOWN,null,Checks.text(reason,128,"reason"),null);}
 public boolean isConfirmed(){return kind==Kind.SUCCESS||kind==Kind.FAILURE;}
}
