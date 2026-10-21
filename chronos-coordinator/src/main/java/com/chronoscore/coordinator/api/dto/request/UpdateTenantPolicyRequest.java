package com.chronoscore.coordinator.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/**
 * Request payload for updating or establishing a tenant execution policy.
 */
public record UpdateTenantPolicyRequest(
        @Positive(message = "maxConcurrentTasks must be positive")
        Integer maxConcurrentTasks,

        @Positive(message = "maxQueuedTasks must be positive")
        Integer maxQueuedTasks,

        @Positive(message = "tasksPerSecond must be positive")
        Double tasksPerSecond,

        @Min(value = 1, message = "burstCapacity must be at least 1")
        Integer burstCapacity,

        @Positive(message = "maxWorkflowExecutions must be positive")
        Integer maxWorkflowExecutions
) {
}
