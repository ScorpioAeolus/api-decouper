package com.api.decouper.core;

import com.api.decouper.entity.LocalTask;
import com.api.decouper.repository.LocalTaskRepository;
import com.api.decouper.spi.TaskHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * Core Service to execute tasks.
 * This can be called by:
 * 1. MQ Consumer (Real-time)
 * 2. Scheduled Task (Compensation)
 */
@Slf4j
@Service
public class TaskExecutorService {

    @Resource
    private TaskHandlerRegistry taskHandlerRegistry;

    @Resource
    private LocalTaskRepository localTaskRepository;

    /**
     * Submit a new task (to be called within a local transaction).
     */
    public void submitTask(String bizType, String bizId, String payload) {
        LocalTask task = LocalTask.builder()
                .bizType(bizType)
                .bizId(bizId)
                .payload(payload)
                .build();
        localTaskRepository.insert(task);
        // Hint: Post-commit, the caller should send an MQ message referencing this task ID.
    }

    /**
     * Entry point for executing a task.
     * Handles CAS locking, Handler lookup, Execution, and State updates.
     *
     * @param taskId The ID of the task to execute.
     * @return true if executed successfully (or if it was already processed).
     */
    public boolean executeTask(Long taskId) {
        // 1. Fetch the latest state
        LocalTask task = localTaskRepository.findById(taskId);
        if (task == null) {
            log.error("Task not found: {}", taskId);
            return false;
        }

        // 2. Check status (Idempotency check)
        if (task.getStatus() == 2) { // SUCCESS
            log.info("Task {} already successful. Skipping.", taskId);
            return true;
        }

        // 3. Try to Lock (CAS)
        // If status is PROCESSING, another thread/node is working on it.
        // If status is INIT or FAIL (retry), we try to move it to PROCESSING.
        boolean locked = localTaskRepository.lockTask(taskId, task.getVersion());
        if (!locked) {
            log.info("Failed to lock task {}. It might be running or version changed.", taskId);
            return false;
        }

        // 4. Find Handler
        TaskHandler handler = taskHandlerRegistry.getHandler(task.getBizType());
        if (handler == null) {
            log.error("No handler found for bizType: {}", task.getBizType());
            // Mark as FAIL or specialized ERROR status
            localTaskRepository.updateStatus(taskId, 3, "No Handler Found");
            alert("No handler found for task " + taskId);
            return false;
        }

        // 5. Execute
        try {
            boolean success = handler.execute(task);
            if (success) {
                // Success
                localTaskRepository.updateStatus(taskId, 2, "Success");
                handler.onSuccess(task);
                return true;
            } else {
                // Logic Failure
                handleFailure(task, handler, new RuntimeException("Handler returned false"));
                return false;
            }
        } catch (Exception e) {
            // System/Exception Failure
            log.error("Exception executing task {}", taskId, e);
            handleFailure(task, handler, e);
            return false;
        }
    }

    private void handleFailure(LocalTask task, TaskHandler handler, Exception e) {
        // Here we could implement retry logic (backoff calculation)
        // For simplicity, we just mark as FAIL (3)
        localTaskRepository.updateStatus(task.getId(), 3, e.getMessage());
        handler.onFail(task, e);
        
        // Alert if necessary
        if (task.getRetryCount() != null && task.getRetryCount() > 5) {
            alert("Task " + task.getId() + " failed too many times.");
        }
    }

    private void alert(String msg) {
        // Integration with Alert System (Email, Slack, etc.)
        log.error("ALERT: {}", msg);
    }
}
