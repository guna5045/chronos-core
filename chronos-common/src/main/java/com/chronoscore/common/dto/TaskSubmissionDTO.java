package com.chronoscore.common.dto;

import com.chronoscore.common.enums.TaskPriority;

import java.time.Duration;
import java.time.Instant;

/**
 * Data Transfer Object representing a task submission across module boundaries.
 */
public record TaskSubmissionDTO(
        String taskType,
        String payload,
        TaskPriority priority,
        String tenantId,
        String idempotencyKey,
        Instant scheduledAt,
        Instant deadline,
        Duration timeout,
        Integer maxAttempts,
        String concurrencyKey
) {
    public TaskSubmissionDTO {
        if (priority == null) {
            priority = TaskPriority.NORMAL;
        }
        if (maxAttempts == null || maxAttempts <= 0) {
            maxAttempts = 3;
        }
    }

    public TaskSubmissionDTO(
            String taskType,
            String payload,
            TaskPriority priority,
            String tenantId,
            String idempotencyKey,
            Instant scheduledAt,
            Instant deadline,
            Duration timeout,
            Integer maxAttempts
    ) {
        this(taskType, payload, priority, tenantId, idempotencyKey, scheduledAt, deadline, timeout, maxAttempts, null);
    }
}
