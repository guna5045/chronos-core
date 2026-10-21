package com.chronoscore.common.dto;

import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight Data Transfer Object summarizing a Task for cluster queries and UI visibility.
 */
public record TaskSummaryDTO(
        UUID taskId,
        String taskType,
        TaskPriority priority,
        TaskStatus status,
        String tenantId,
        Instant createdAt,
        int currentAttempt,
        int maxAttempts,
        long version
) {
}
