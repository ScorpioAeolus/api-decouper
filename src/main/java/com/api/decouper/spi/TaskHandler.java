package com.api.decouper.spi;
import com.api.decouper.api.*;
import com.infeed.spi.api.ISpiProvider;
import com.infeed.spi.annotation.SpiFunctionPoint;
@SpiFunctionPoint(name="decouperTaskHandler")
public interface TaskHandler<P,R> extends ISpiProvider<TaskContext,Object> {
 ExecutionResult<R> execute(TaskContext context,P request) throws Exception;
 default RecoveryDecision<R> recover(TaskContext context,P request) throws Exception {return RecoveryDecision.manual("RECOVERY_NOT_IMPLEMENTED");}
 void applyResult(TaskContext context,P request,ExecutionResult<R> result) throws Exception;
 TaskCodec<P,R> codec();
 default boolean condition(TaskContext context){return true;}
}
