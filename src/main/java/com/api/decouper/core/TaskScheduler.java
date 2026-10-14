package com.api.decouper.core;
import com.api.decouper.entity.*;import com.api.decouper.repository.*;import com.api.decouper.autoconfigure.*;import com.api.decouper.observability.*;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;import java.util.concurrent.locks.ReentrantLock;
/** Multi-instance polling: every node scans, only a fenced CAS winner executes. */
public final class TaskScheduler implements AutoCloseable {
 private final LocalTaskRepository repository;private final TaskExecutorService engine;private final DecouperProperties config;private final RuntimeSignals signals;
 private final ThreadPoolExecutor workers;private final ScheduledExecutorService ticker;private final ReentrantLock scanLock=new ReentrantLock();
 private final AtomicBoolean closed=new AtomicBoolean(),started=new AtomicBoolean();
 public TaskScheduler(LocalTaskRepository r,TaskExecutorService e,DecouperProperties p,RuntimeSignals s){
  repository=r;engine=e;config=p;signals=s;
  workers=new ThreadPoolExecutor(p.getConcurrency(),p.getConcurrency(),0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(p.getConcurrency()),run->{Thread t=new Thread(run,"decouper-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
  ticker=Executors.newSingleThreadScheduledExecutor(run->{Thread t=new Thread(run,"decouper-scan");t.setDaemon(true);return t;});
 }
 public void start(){if(config.isSchedulingEnabled()&&!closed.get()&&started.compareAndSet(false,true))ticker.scheduleWithFixedDelay(()->{try{scan();}catch(RuntimeException ignored){/* lastScanSuccess drives the stalled-scan alert. */}},0,config.getPollIntervalMillis(),TimeUnit.MILLISECONDS);}
 public void scan(){
  if(closed.get()||!scanLock.tryLock())return;
  try{
   long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(config.getScanBudgetMillis());
   for(LocalTask t:repository.findExpired(config.getBatchSize()))repository.recoverExpired(t);
   int perState=Math.max(1,config.getMaxScanTasks()/4);
   for(TaskStatus state:Arrays.asList(TaskStatus.RESULT_READY,TaskStatus.UNKNOWN,TaskStatus.READY,TaskStatus.RETRY_WAIT)){
    long cursor=0,id=0;int count=0;
    while(count<perState&&System.nanoTime()<deadline&&!closed.get()){
     List<LocalTask> page=repository.findDue(state,cursor,id,Math.min(config.getBatchSize(),perState-count));if(page.isEmpty())break;
     for(LocalTask t:page){
      try{workers.execute(()->{try{engine.executeTask(t.getId());}catch(RuntimeException ex){signals.workerFailureSince.compareAndSet(0,System.currentTimeMillis());}});}
      catch(RejectedExecutionException ex){break;}
     }
     LocalTask last=page.get(page.size()-1);cursor=last.getNextExecuteTime();id=last.getId();count+=page.size();
    }
   }
   signals.lastScanSuccess.set(System.currentTimeMillis());
  }finally{scanLock.unlock();}
 }
 public void close(){if(closed.compareAndSet(false,true)){ticker.shutdownNow();workers.shutdown();try{if(!workers.awaitTermination(10,TimeUnit.SECONDS))workers.shutdownNow();}catch(InterruptedException ex){workers.shutdownNow();Thread.currentThread().interrupt();}}}
}
