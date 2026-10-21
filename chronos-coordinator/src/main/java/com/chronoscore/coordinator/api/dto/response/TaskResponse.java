package com.chronoscore.coordinator.api.dto.response;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * REST response model representing a Chronos Task.
 */
public record TaskResponse(
        UUID taskId,
        String taskType,
        String payload,
        TaskPriority priority,
        TaskStatus status,
        String tenantId,
        String idempotencyKey,
        String concurrencyKey,
        Instant createdAt,
        Instant scheduledAt,
        Instant deadline,
        Long timeoutMs,
        int maxAttempts,
        int currentAttempt,
        long version
) {

    public static TaskResponse from(Task task) {
        if (task == null) {
            return null;
        }
        return new TaskResponse(
                task.getTaskId(),
                task.getTaskType(),
                task.getPayload(),
                task.getPriority(),
                task.getStatus(),
                task.getTenantId(),
                task.getIdempotencyKey(),
                task.getConcurrencyKey(),
                task.getCreatedAt(),
                task.getScheduledAt(),
                task.getDeadline(),
                task.getTimeout() != null ? task.getTimeout().toMillis() : null,
                task.getMaxAttempts(),
                task.getCurrentAttempt(),
                task.getVersion()
        );
    }
}
