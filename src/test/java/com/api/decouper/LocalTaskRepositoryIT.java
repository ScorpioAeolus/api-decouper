package com.api.decouper;
import com.api.decouper.api.*;import com.api.decouper.core.*;import com.api.decouper.entity.*;
import org.junit.jupiter.api.Test;import java.util.*;import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class LocalTaskRepositoryIT extends MySqlFixture {
 long task(){return tx.execute(s->repo.insert(new TaskRequest("PAY",1,"b",UUID.randomUUID().toString(),"{}",1)));}
 @Test void concurrentClaimOnlyOneWinnerAndOldTokenCannotFinish() throws Exception {
  long id=task();LocalTask snapshot=repo.findById(id);ExecutorService pool=Executors.newFixedThreadPool(2);
  try {CountDownLatch start=new CountDownLatch(1);Callable<Boolean> race=()->{start.await();return repo.claim(snapshot,30000)!=null;};
   Future<Boolean>a=pool.submit(race),b=pool.submit(race);start.countDown();assertNotEquals(a.get(),b.get());
  } finally {pool.shutdownNow();}
  snapshot.setStatus(TaskStatus.EXECUTING);snapshot.setExecutionToken("old-token");
  assertFalse(repo.defer(snapshot,TaskStatus.UNKNOWN,repo.dbNow(),"STALE"));
 }
 @Test void notDueCannotBeClaimedAndExpiredCallBecomesUnknown() {
  long id=task();jdbc.update("UPDATE local_task SET next_execute_time=? WHERE id=?",repo.dbNow().plusSeconds(60).toEpochMilli(),id);
  assertNull(repo.claim(repo.findById(id),30000));
  jdbc.update("UPDATE local_task SET next_execute_time=0 WHERE id=?",id);
  LocalTask running=repo.claim(repo.findById(id),30000);assertNotNull(running);
  assertFalse(repo.recoverExpired(running));
  jdbc.update("UPDATE local_task SET lease_until=0 WHERE id=?",id);
  assertTrue(repo.recoverExpired(repo.findById(id)));assertEquals(TaskStatus.UNKNOWN,repo.findById(id).getStatus());
 }
 @Test void idempotentInsertRaceReturnsSameId() throws Exception {
  TaskRequest r=new TaskRequest("PAY",1,"b","duplicate","{}",1);ExecutorService p=Executors.newFixedThreadPool(2);
  try {Callable<Long> c=()->tx.execute(s->repo.insert(r));Future<Long>a=p.submit(c),b=p.submit(c);assertEquals(a.get(),b.get());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM local_task",Integer.class));}finally{p.shutdownNow();}
 }
}
