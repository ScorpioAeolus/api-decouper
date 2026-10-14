package com.api.decouper.repository;
import com.api.decouper.api.*;
import com.api.decouper.entity.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** All state transitions and their audit event are atomic. UTC epoch milliseconds avoid JDBC timezone ambiguity. */
public class LocalTaskRepository {
 private static final String NOW="CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)";
 private final JdbcTemplate jdbc; private final TransactionTemplate shortTx;
 public LocalTaskRepository(JdbcTemplate jdbc,DataSourceTransactionManager manager){
  if(jdbc.getDataSource()!=manager.getDataSource())throw new IllegalArgumentException("Datasource and transaction manager must match by identity");
  this.jdbc=jdbc;shortTx=new TransactionTemplate(manager);shortTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);shortTx.setTimeout(10);
 }
 public JdbcTemplate jdbc(){return jdbc;}
 public void requireBusinessTransaction(){
  if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("Writable business transaction required");
  Object resource=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
  if(!(resource instanceof org.springframework.jdbc.datasource.ConnectionHolder))throw new IllegalStateException("Transaction must use the bound datasource");
  try {if(((org.springframework.jdbc.datasource.ConnectionHolder)resource).getConnection().getAutoCommit())throw new IllegalStateException("Bound datasource must be enlisted in a transaction");}catch(SQLException e){throw new IllegalStateException("Cannot verify datasource transaction",e);}
 }
 public Instant dbNow(){return Instant.ofEpochMilli(jdbc.queryForObject("SELECT "+NOW,Long.class));}
 public <T>T transaction(java.util.function.Supplier<T> body){return shortTx.execute(s->body.get());}
 public long insert(TaskRequest r){
  requireBusinessTransaction();long now=dbNow().toEpochMilli();KeyHolder kh=new GeneratedKeyHolder();
  // Atomic unique-key insert also serializes concurrent callers. SELECT FOR UPDATE below reads latest committed row under REPEATABLE READ.
  jdbc.update(c->{PreparedStatement p=c.prepareStatement("INSERT INTO local_task (biz_type,handler_version,biz_id,operation_key,payload,payload_version,status,next_execute_time,create_time,update_time) VALUES(?,?,?,?,?,?,'READY',?,?,?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)",Statement.RETURN_GENERATED_KEYS);
   p.setString(1,r.getBizType());p.setInt(2,r.getHandlerVersion());p.setString(3,r.getBizId());p.setString(4,r.getOperationKey());p.setString(5,r.getPayload());p.setInt(6,r.getPayloadVersion());p.setLong(7,now);p.setLong(8,now);p.setLong(9,now);return p;},kh);
  LocalTask t=jdbc.queryForObject("SELECT * FROM local_task WHERE operation_key=? FOR UPDATE",mapper,r.getOperationKey());
  if(!t.getBizType().equals(r.getBizType())||t.getHandlerVersion()!=r.getHandlerVersion()||!t.getBizId().equals(r.getBizId())||!t.getPayload().equals(r.getPayload())||t.getPayloadVersion()!=r.getPayloadVersion())throw new IllegalArgumentException("operationKey reused with different input");
  jdbc.update("INSERT INTO local_task_event(task_id,from_status,to_status,version,reason,create_time) SELECT id,NULL,'READY',0,'SUBMITTED',create_time FROM local_task WHERE id=? AND NOT EXISTS(SELECT 1 FROM local_task_event e WHERE e.task_id=?)",t.getId(),t.getId());
  return t.getId();
 }
 public LocalTask findById(long id){List<LocalTask> l=jdbc.query("SELECT * FROM local_task WHERE id=?",mapper,id);return l.isEmpty()?null:l.get(0);}
 public LocalTask lockOwned(LocalTask t){
  List<LocalTask> l=jdbc.query("SELECT * FROM local_task WHERE id=? AND status=? AND execution_token=? FOR UPDATE",mapper,t.getId(),t.getStatus().name(),t.getExecutionToken());
  if(l.isEmpty())throw new IllegalStateException("Task ownership lost");return l.get(0);
 }
 public LocalTask claim(LocalTask snapshot,long leaseMillis){
  if(!snapshot.getStatus().runnable())return null;
  return transaction(()->{
   TaskStatus phase=snapshot.getStatus().runningPhase();String token=UUID.randomUUID().toString();
   String counter=phase==TaskStatus.EXECUTING?"execute_attempts":phase==TaskStatus.RECOVERING?"recover_attempts":"apply_attempts";
   int n=jdbc.update("UPDATE local_task SET status=?,execution_token=?,lease_until="+NOW+"+?,version=version+1,"+counter+"="+counter+"+1,update_time="+NOW+" WHERE id=? AND version=? AND status=? AND next_execute_time<="+NOW,
    phase.name(),token,leaseMillis,snapshot.getId(),snapshot.getVersion(),snapshot.getStatus().name());
   if(n==0)return null;LocalTask t=findById(snapshot.getId());event(t,snapshot.getStatus(),phase,"CLAIMED");return t;
  });
 }
 public boolean defer(LocalTask t,TaskStatus to,Instant next,String reason){return finish(t,to,next,reason,null,0,null,null);}
 public boolean saveResult(LocalTask t,String payload,int resultVersion,ExecutionResult.Kind outcome,String reason){
  if(outcome!=ExecutionResult.Kind.SUCCESS&&outcome!=ExecutionResult.Kind.FAILURE)throw new IllegalArgumentException("confirmed outcome required");
  if(payload!=null&&payload.length()>65535)throw new IllegalArgumentException("Result too large");
  return finish(t,TaskStatus.RESULT_READY,dbNow(),reason,payload,resultVersion,outcome.name(),reason);
 }
 private boolean finish(LocalTask t,TaskStatus to,Instant next,String reason,String payload,int rv,String outcome,String resultReason){
  if(!t.getStatus().canFinishAs(to))throw new IllegalArgumentException("Invalid transition");
  return transaction(()->finishInTransaction(t,to,next,reason,payload,rv,outcome,resultReason));
 }
 private boolean finishInTransaction(LocalTask t,TaskStatus to,Instant next,String reason,String payload,int rv,String outcome,String resultReason){
  String resultSql=outcome==null?"":",result_payload=?,result_version=?,result_outcome=?,result_reason=?";
  List<Object> args=new ArrayList<>(Arrays.asList(to.name(),next.toEpochMilli(),reason));
  if(outcome!=null)args.addAll(Arrays.asList(payload,rv,outcome,resultReason));
  args.addAll(Arrays.asList(t.getId(),t.getStatus().name(),t.getExecutionToken()));
  int n=jdbc.update("UPDATE local_task SET status=?,next_execute_time=?,last_error_code=?,execution_token=NULL,lease_until=NULL,version=version+1,update_time="+NOW+resultSql+" WHERE id=? AND status=? AND execution_token=?",args.toArray());
  if(n>0){event(findById(t.getId()),t.getStatus(),to,reason);return true;}return false;
 }
 /** Must be called in the same transaction that updates the business row. Never starts REQUIRES_NEW. */
 public void completeApplied(LocalTask t){
  int n=jdbc.update("UPDATE local_task SET status='DONE',completed_time="+NOW+",update_time="+NOW+",version=version+1,execution_token=NULL,lease_until=NULL,last_error_code=NULL WHERE id=? AND status='APPLYING' AND execution_token=?",t.getId(),t.getExecutionToken());
  if(n!=1)throw new IllegalStateException("Task ownership lost during apply");event(findById(t.getId()),TaskStatus.APPLYING,TaskStatus.DONE,"APPLIED");
 }
 public boolean renew(LocalTask t,long leaseMillis){return jdbc.update("UPDATE local_task SET lease_until="+NOW+"+? WHERE id=? AND status=? AND execution_token=? AND lease_until>"+NOW,leaseMillis,t.getId(),t.getStatus().name(),t.getExecutionToken())==1;}
 public boolean recoverExpired(LocalTask t){
  if(!t.getStatus().running())return false;
  return transaction(()->{
   TaskStatus next=t.getStatus()==TaskStatus.APPLYING?TaskStatus.RESULT_READY:TaskStatus.UNKNOWN;
   int n=jdbc.update("UPDATE local_task SET status=?,execution_token=NULL,lease_until=NULL,version=version+1,next_execute_time="+NOW+",update_time="+NOW+",last_error_code='LEASE_EXPIRED' WHERE id=? AND version=? AND status=? AND execution_token=? AND lease_until<="+NOW,next.name(),t.getId(),t.getVersion(),t.getStatus().name(),t.getExecutionToken());
   if(n==1)event(findById(t.getId()),t.getStatus(),next,"LEASE_EXPIRED");return n==1;
  });
 }
 public List<LocalTask> findDue(TaskStatus status,long timeCursor,long idCursor,int limit){return jdbc.query("SELECT * FROM local_task WHERE status=? AND next_execute_time<="+NOW+" AND (next_execute_time>? OR (next_execute_time=? AND id>?)) ORDER BY next_execute_time,id LIMIT ?",mapper,status.name(),timeCursor,timeCursor,idCursor,limit);}
 public List<LocalTask> findExpired(int limit){return jdbc.query("SELECT * FROM local_task WHERE status IN ('EXECUTING','RECOVERING','APPLYING') AND lease_until<="+NOW+" ORDER BY lease_until,id LIMIT ?",mapper,limit);}
 public boolean resume(long id,int version,String reason){
  Checks.text(reason,128,"operator reason");
  return transaction(()->{
   LocalTask t=findById(id);if(t==null||t.getStatus()!=TaskStatus.MANUAL||t.getVersion()!=version)return false;
   TaskStatus next=t.getResultOutcome()!=null?TaskStatus.RESULT_READY:TaskStatus.UNKNOWN;
   int n=jdbc.update("UPDATE local_task SET status=?,version=version+1,recover_attempts=0,apply_attempts=0,next_execute_time="+NOW+",update_time="+NOW+",last_error_code=NULL WHERE id=? AND version=? AND status='MANUAL'",next.name(),id,version);
   if(n==1)event(findById(id),TaskStatus.MANUAL,next,reason);return n==1;
  });
 }
 private void event(LocalTask t,TaskStatus from,TaskStatus to,String reason){jdbc.update("INSERT INTO local_task_event(task_id,from_status,to_status,version,reason,create_time) VALUES(?,?,?,?,?,"+NOW+")",t.getId(),from==null?null:from.name(),to.name(),t.getVersion(),reason);}
 private static final RowMapper<LocalTask> mapper=(rs,row)->{
  LocalTask t=new LocalTask();t.setId(rs.getLong("id"));t.setBizType(rs.getString("biz_type"));t.setHandlerVersion(rs.getInt("handler_version"));t.setBizId(rs.getString("biz_id"));t.setOperationKey(rs.getString("operation_key"));t.setPayload(rs.getString("payload"));t.setPayloadVersion(rs.getInt("payload_version"));t.setStatus(TaskStatus.valueOf(rs.getString("status")));t.setResultPayload(rs.getString("result_payload"));t.setResultVersion(rs.getInt("result_version"));t.setResultOutcome(rs.getString("result_outcome"));t.setResultReason(rs.getString("result_reason"));t.setExecutionToken(rs.getString("execution_token"));t.setLeaseUntil((Long)rs.getObject("lease_until"));t.setVersion(rs.getInt("version"));t.setExecuteAttempts(rs.getInt("execute_attempts"));t.setRecoverAttempts(rs.getInt("recover_attempts"));t.setApplyAttempts(rs.getInt("apply_attempts"));t.setNextExecuteTime(rs.getLong("next_execute_time"));t.setLastErrorCode(rs.getString("last_error_code"));t.setCreateTime(rs.getLong("create_time"));t.setUpdateTime(rs.getLong("update_time"));t.setCompletedTime((Long)rs.getObject("completed_time"));return t;
 };
}
