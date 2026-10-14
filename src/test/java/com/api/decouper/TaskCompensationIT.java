package com.api.decouper;
import com.api.decouper.api.*;import com.api.decouper.core.*;import com.api.decouper.entity.*;import com.api.decouper.autoconfigure.*;import com.api.decouper.observability.*;
import org.junit.jupiter.api.Test;import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class TaskCompensationIT extends MySqlFixture {
 @Test void pollingCompletesTaskWithoutMqAndSlowTaskDoesNotBlockScan() throws Exception {
  TaskSubmissionService submission=new TaskSubmissionService(repo,id->{throw new Exception("MQ unavailable");},new RuntimeSignals());
  long id=tx.execute(s->submission.submit(new TaskRequest("PAY",1,"b","op","{}",1)));
  TaskExecutionIT.Payment h=new TaskExecutionIT.Payment();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  h.onApply=()->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}};
  DecouperProperties p=new DecouperProperties();RuntimeSignals signals=new RuntimeSignals();
  try(TaskExecutorService engine=new TaskExecutorService(repo,(t,v,c)->h,p,signals);TaskScheduler scheduler=new TaskScheduler(repo,engine,p,signals)){
   long begin=System.nanoTime();scheduler.scan();assertTrue(System.nanoTime()-begin<TimeUnit.SECONDS.toNanos(2));assertTrue(entered.await(5,TimeUnit.SECONDS));
   release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(repo.findById(id).getStatus()!=TaskStatus.DONE&&System.nanoTime()<deadline)Thread.yield();
   assertEquals(TaskStatus.DONE,repo.findById(id).getStatus());assertEquals(1,h.calls.get());
  }finally{release.countDown();}
 }
 @Test void expiredApplyingOnlyRepeatsLocalResult() {
  long id=tx.execute(s->repo.insert(new TaskRequest("PAY",1,"b","op","{}",1)));LocalTask t=repo.claim(repo.findById(id),1000);
  repo.saveResult(t,"accepted",1,ExecutionResult.Kind.SUCCESS,"CONFIRMED");repo.claim(repo.findById(id),1000);
  jdbc.update("UPDATE local_task SET lease_until=0 WHERE id=?",id);assertTrue(repo.recoverExpired(repo.findById(id)));
  TaskExecutionIT.Payment h=new TaskExecutionIT.Payment();try(TaskExecutorService e=new TaskExecutorService(repo,(a,b,c)->h,new DecouperProperties(),new RuntimeSignals())){e.executeTask(id);}
  assertEquals(0,h.calls.get());assertEquals(0,h.queries.get());assertEquals(1,h.applies.get());assertEquals(TaskStatus.DONE,repo.findById(id).getStatus());
 }
}
