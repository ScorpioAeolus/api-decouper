-- Fresh-install schema. Do not apply over the original prototype table without a reviewed migration.
-- All times are UTC epoch milliseconds; identifiers use case-sensitive collation.
CREATE TABLE local_task (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 biz_type VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,handler_version INT NOT NULL,
 biz_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,operation_key VARCHAR(160) COLLATE utf8mb4_bin NOT NULL,
 payload MEDIUMTEXT NOT NULL,payload_version INT NOT NULL,status VARCHAR(20) NOT NULL,
 result_payload MEDIUMTEXT NULL,result_version INT NOT NULL DEFAULT 0,result_outcome VARCHAR(16),result_reason VARCHAR(128),
 execution_token VARCHAR(36),lease_until BIGINT,version INT NOT NULL DEFAULT 0,
 execute_attempts INT NOT NULL DEFAULT 0,recover_attempts INT NOT NULL DEFAULT 0,apply_attempts INT NOT NULL DEFAULT 0,
 next_execute_time BIGINT NOT NULL,last_error_code VARCHAR(128),create_time BIGINT NOT NULL,update_time BIGINT NOT NULL,completed_time BIGINT,
 UNIQUE KEY uk_operation_key(operation_key),KEY ix_due(status,next_execute_time,id),KEY ix_lease(status,lease_until,id),KEY ix_business(biz_type,biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE local_task_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,task_id BIGINT NOT NULL,from_status VARCHAR(20),to_status VARCHAR(20) NOT NULL,
 version INT NOT NULL,execution_token VARCHAR(36),result_outcome VARCHAR(16),reason VARCHAR(128),create_time BIGINT NOT NULL,KEY ix_task(task_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
