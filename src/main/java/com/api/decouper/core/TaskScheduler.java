package com.api.decouper.core;

import com.api.decouper.entity.LocalTask;
import com.api.decouper.repository.LocalTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * Scheduled Task to pick up pending or failed tasks.
 * Acts as a compensation mechanism.
 */
@Slf4j
@Component
public class TaskScheduler {

    @Resource
    private LocalTaskRepository localTaskRepository;

    @Resource
    private TaskExecutorService taskExecutorService;

    /**
     * Run every 5 seconds.
     */
    @Scheduled(cron = "0/5 * * * * ?")
    public void scheduleTask() {
        log.debug("Scheduler running...");
        
        // Fetch tasks that are INIT or RETRY-ready
        List<LocalTask> tasks = localTaskRepository.findPendingTasks(20); // Batch size 20
        
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        for (LocalTask task : tasks) {
            try {
                // Execute each task
                // The executor handles locking (CAS), so it's safe to run in parallel threads if needed.
                taskExecutorService.executeTask(task.getId());
            } catch (Exception e) {
                log.error("Scheduler failed to trigger task {}", task.getId(), e);
            }
        }
    }
}
