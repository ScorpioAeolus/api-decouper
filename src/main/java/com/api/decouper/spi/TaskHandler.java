package com.api.decouper.spi;

import com.api.decouper.entity.LocalTask;

/**
 * SPI Interface for Business Logic Execution.
 * Implementations should register themselves via the TaskHandlerRegistry.
 */
public interface TaskHandler {

    /**
     * Unique identifier for this business logic.
     * Matches the 'bizType' in LocalTask table.
     * e.g., "PAYMENT_CREATE"
     */
    String getBizType();

    /**
     * Main execution logic.
     * @param task The task context containing payload and metadata.
     * @return true if execution was successful, false otherwise.
     * @throws Exception if execution fails significantly.
     */
    boolean execute(LocalTask task) throws Exception;

    /**
     * Callback when execution succeeds.
     */
    default void onSuccess(LocalTask task) {
        // Optional hook
    }

    /**
     * Callback when execution fails.
     * @param task The task
     * @param e The exception (if any)
     */
    default void onFail(LocalTask task, Exception e) {
        // Optional hook
    }
}
