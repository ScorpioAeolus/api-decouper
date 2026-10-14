package com.api.decouper.observability;
import java.util.concurrent.atomic.AtomicLong;
public final class RuntimeSignals {
 public final AtomicLong mqFailureSince=new AtomicLong();
 public final AtomicLong lastScanSuccess=new AtomicLong(System.currentTimeMillis());
 public final AtomicLong workerFailureSince=new AtomicLong();
 public final AtomicLong casMisses=new AtomicLong();
 public void mqFailure(){mqFailureSince.compareAndSet(0,System.currentTimeMillis());}
 public void mqSuccess(){mqFailureSince.set(0);}
}
