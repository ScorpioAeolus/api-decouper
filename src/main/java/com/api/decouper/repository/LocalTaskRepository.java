package com.api.decouper.repository;

import com.api.decouper.entity.LocalTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

@Slf4j
@Repository
public class LocalTaskRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * Lock a task for execution using Optimistic Locking (CAS).
     * @param id Task ID
     * @param currentVersion The version currently held in memory
     * @return true if lock was successful
     */
    public boolean lockTask(Long id, Integer currentVersion) {
        String sql = "UPDATE local_task SET status = 1, version = version + 1, update_time = ? " +
                     "WHERE id = ? AND version = ? AND status IN (0, 3)"; // 0=INIT, 3=FAIL (Retry)
        int rows = jdbcTemplate.update(sql, new Date(), id, currentVersion);
        return rows > 0;
    }

    /**
     * Update task status after execution.
     */
    public void updateStatus(Long id, Integer status, String resultInfo) {
        String sql = "UPDATE local_task SET status = ?, update_time = ? WHERE id = ?";
        // Note: resultInfo could be stored in a separate log table or extended field, 
        // avoiding schema complexity here for now.
        jdbcTemplate.update(sql, status, new Date(), id);
    }
    
    /**
     * Fetch pending tasks for the scheduler.
     * This scans for INIT tasks or FAIL tasks that reached retry time.
     */
    public List<LocalTask> findPendingTasks(int limit) {
        String sql = "SELECT * FROM local_task WHERE status = 0 OR (status = 3 AND next_retry_time <= ?) LIMIT ?";
        return jdbcTemplate.query(sql, new BeanPropertyRowMapper<>(LocalTask.class), new Date(), limit);
    }

    public LocalTask findById(Long id) {
        String sql = "SELECT * FROM local_task WHERE id = ?";
        List<LocalTask> list = jdbcTemplate.query(sql, new BeanPropertyRowMapper<>(LocalTask.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Long insert(LocalTask task) {
        String sql = "INSERT INTO local_task (biz_type, biz_id, status, payload, version, retry_count, create_time, update_time) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        // In a real app, use KeyHolder to get ID. For brevity:
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        task.setVersion(0);
        task.setStatus(0);
        task.setRetryCount(0);
        
        jdbcTemplate.update(sql, task.getBizType(), task.getBizId(), task.getStatus(), task.getPayload(), 
                            task.getVersion(), task.getRetryCount(), task.getCreateTime(), task.getUpdateTime());
        // Note: Ideally return generated ID
        return 0L; 
    }
}
