package com.api.decouper;
import com.api.decouper.core.*;import com.api.decouper.api.*;import com.api.decouper.autoconfigure.*;import com.api.decouper.observability.*;import com.api.decouper.repository.*;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class TaskSchedulerTest {
 @Test void closedEngineNeverClaimsWorkAndRejectsTransactionalTrigger() {
  DecouperProperties p=new DecouperProperties();p.setConcurrency(1);LocalTaskRepository r=mock(LocalTaskRepository.class);
  TaskExecutorService e=new TaskExecutorService(r,(a,b,c)->null,p,new RuntimeSignals());e.close();assertEquals(TaskTriggerResult.BUSY,e.executeTask(1));
  assertThrows(IllegalArgumentException.class,()->{DecouperProperties invalid=new DecouperProperties();invalid.setConcurrency(0);invalid.validate();});
 }
}
