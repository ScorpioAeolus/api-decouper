package com.api.decouper;
import com.api.decouper.api.*;import com.api.decouper.core.*;import com.api.decouper.entity.*;import com.api.decouper.spi.*;
import com.api.decouper.autoconfigure.DecouperProperties;import com.api.decouper.observability.RuntimeSignals;
import org.junit.jupiter.api.Test;import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class TaskExecutionIT extends MySqlFixture {
 static class Payment implements TaskHandler<String,String> {
  AtomicInteger calls=new AtomicInteger(),applies=new AtomicInteger(),queries=new AtomicInteger();boolean timeout,failApply,retry;Runnable onApply;
  public TaskCodec<String,String> codec(){return TaskCodec.strings();}
  public ExecutionResult<String> execute(TaskContext c,String p) throws Exception {
   assertFalse(TransactionSynchronizationManager.isActualTransactionActive());calls.incrementAndGet();
   if(timeout)throw new java.net.SocketTimeoutException("untrusted response details");
   if(retry)return ExecutionResult.retry(Instant.now().plusSeconds(60),"SAFE_IDEMPOTENT");
   return ExecutionResult.success("accepted");
  }
  public RecoveryDecision<String> recover(TaskContext c,String p){queries.incrementAndGet();return RecoveryDecision.confirmed(ExecutionResult.success("accepted"));}
  public void applyResult(TaskContext c,String p,ExecutionResult<String> r){
   assertTrue(TransactionSynchronizationManager.isActualTransactionActive());applies.incrementAndGet();if(onApply!=null)onApply.run();if(failApply)throw new IllegalStateException("local db temporary");
  }
 }
 long create(){return tx.execute(st->repo.insert(new TaskRequest("PAY",1,"order1","PAY:order1","{}",1)));}
 TaskExecutorService engine(Payment h){return new TaskExecutorService(repo,(t,v,c)->h,new DecouperProperties(),new RuntimeSignals());}
 void due(long id){jdbc.update("UPDATE local_task SET next_execute_time=0 WHERE id=?",id);}
 @Test void resultApplyFailureDoesNotRepeatExternalCallAndRollsBackBusiness() {
  long id=create();jdbc.update("INSERT INTO sample_order VALUES('order1','NEW')");Payment p=new Payment();p.failApply=true;
  p.onApply=()->jdbc.update("UPDATE sample_order SET status='ACCEPTED' WHERE id='order1'");
  try(TaskExecutorService e=engine(p)){
   e.executeTask(id);assertEquals(TaskStatus.RESULT_READY,repo.findById(id).getStatus());assertEquals("NEW",jdbc.queryForObject("SELECT status FROM sample_order",String.class));
   p.failApply=false;due(id);e.executeTask(id);assertEquals(TaskStatus.DONE,repo.findById(id).getStatus());assertEquals(1,p.calls.get());assertEquals(2,p.applies.get());assertEquals("ACCEPTED",jdbc.queryForObject("SELECT status FROM sample_order",String.class));
  }
 }
 @Test void timeoutGoesToRecoveryNotExternalRetry() {
  long id=create();Payment p=new Payment();p.timeout=true;
  try(TaskExecutorService e=engine(p)){
   e.executeTask(id);assertEquals(TaskStatus.UNKNOWN,repo.findById(id).getStatus());assertEquals(0,p.applies.get());
   due(id);e.executeTask(id);assertEquals(TaskStatus.DONE,repo.findById(id).getStatus());assertEquals(1,p.calls.get());assertEquals(1,p.queries.get());
  }
 }
 @Test void leaseExpiredExecutionUsesRecoveryAndApplyLeaseUsesOnlyApply() {
  long id=create();LocalTask t=repo.claim(repo.findById(id),1000);jdbc.update("UPDATE local_task SET lease_until=0 WHERE id=?",id);repo.recoverExpired(repo.findById(id));
  Payment p=new Payment();try(TaskExecutorService e=engine(p)){e.executeTask(id);assertEquals(0,p.calls.get());assertEquals(1,p.queries.get());assertEquals(TaskStatus.DONE,repo.findById(id).getStatus());}
  assertFalse(repo.saveResult(t,"late",1,ExecutionResult.Kind.SUCCESS,"LATE"));
 }
 @Test void repeatedMqCannotBypassBackoff() {
  long id=create();Payment p=new Payment();p.retry=true;
  try(TaskExecutorService e=engine(p)){e.executeTask(id);e.executeTask(id);assertEquals(1,p.calls.get());assertEquals(TaskStatus.RETRY_WAIT,repo.findById(id).getStatus());}
 }
 @Test void concurrentTriggersCallExternalOnlyOnce() throws Exception {
  long id=create();Payment p=new Payment();ExecutorService pool=Executors.newFixedThreadPool(2);
  try(TaskExecutorService e=engine(p)){CountDownLatch start=new CountDownLatch(1);Callable<TaskTriggerResult> c=()->{start.await();return e.executeTask(id);};Future<?>a=pool.submit(c),b=pool.submit(c);start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(1,p.calls.get());assertEquals(TaskStatus.DONE,repo.findById(id).getStatus());}finally{pool.shutdownNow();}
 }
 @Test void applyingRowLockPreventsLeaseReclaimerFromOverwritingCommit() throws Exception {
  long id=create();Payment p=new Payment();CountDownLatch applying=new CountDownLatch(1),release=new CountDownLatch(1);
  p.onApply=()->{applying.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new RuntimeException(ex);}};
  ExecutorService workers=Executors.newFixedThreadPool(2);
  try(TaskExecutorService e=engine(p)){
   Future<?> exec=workers.submit(()->e.executeTask(id));assertTrue(applying.await(5,TimeUnit.SECONDS));
   LocalTask snapshot=repo.findById(id);
   Future<Boolean> reclaim=workers.submit(()->repo.recoverExpired(snapshot));release.countDown();exec.get(10,TimeUnit.SECONDS);assertFalse(reclaim.get(10,TimeUnit.SECONDS));assertEquals(TaskStatus.DONE,repo.findById(id).getStatus());
  }finally{release.countDown();workers.shutdownNow();}
 }
 @Test void invalidResultAndMissingRouteStopSafely() {
  long id=create();Payment p=new Payment(){public ExecutionResult<String> execute(TaskContext c,String s){return null;}};
  try(TaskExecutorService e=engine(p)){e.executeTask(id);assertEquals(TaskStatus.UNKNOWN,repo.findById(id).getStatus());}
 }
}
