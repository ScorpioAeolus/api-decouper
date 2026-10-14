package com.api.decouper.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Local Transaction Task Entity
 * Represents the persistent state of a decoupled task.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocalTask {

    /**
     * Primary Key
     */
    private Long id;

    /**
     * Business Type Identifier (Maps to TaskHandler)
     * e.g., "PAYMENT_INIT", "ORDER_CANCEL"
     */
    private String bizType;

    /**
     * Business ID (e.g., Order No)
     */
    private String bizId;

    /**
     * Task Status
     * 0: INIT
     * 1: PROCESSING
     * 2: SUCCESS
     * 3: FAIL
     */
    private Integer status;

    /**
     * Execution Context / Parameters (JSON)
     */
    private String payload;

    /**
     * Retry Count
     */
    private Integer retryCount;

    /**
     * Next Retry Timestamp
     */
    private Date nextRetryTime;

    /**
     * Optimistic Lock Version
     */
    private Integer version;

    /**
     * Creation Time
     */
    private Date createTime;

    /**
     * Update Time
     */
    private Date updateTime;
}
