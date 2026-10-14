package com.api.decouper.core;
import com.api.decouper.repository.LocalTaskRepository;
/** Expose only behind the host's authorization/audit boundary. */
public final class TaskRecoveryService {
 private final LocalTaskRepository repository;
 public TaskRecoveryService(LocalTaskRepository r){repository=r;}
 public boolean resume(long id,int expectedVersion,String reason){return repository.resume(id,expectedVersion,reason);}
}
