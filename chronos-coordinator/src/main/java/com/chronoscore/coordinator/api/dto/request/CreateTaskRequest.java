package com.chronoscore.coordinator.api.dto.request;

import com.chronoscore.common.enums.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;

/**
 * Request payload for creating and scheduling a Chronos Task via REST.
 */
public record CreateTaskRequest(
        @NotBlank(message = "taskType must not be blank")
        @Size(max = 128, message = "taskType cannot exceed 128 characters")
        String taskType,

        @NotBlank(message = "payload must not be blank")
        @Size(max = 65536, message = "payload cannot exceed 64KB")
        String payload,

        @NotNull(message = "priority must not be null")
        TaskPriority priority,

        @Size(max = 128, message = "tenantId cannot exceed 128 characters")
        String tenantId,

        @Size(max = 256, message = "idempotencyKey cannot exceed 256 characters")
        String idempotencyKey,

        Instant scheduledAt,

        Instant deadline,

        Duration timeout,

        @Positive(message = "maxAttempts must be positive")
        Integer maxAttempts,

        @Size(max = 256, message = "concurrencyKey cannot exceed 256 characters")
        String concurrencyKey
) {
}
