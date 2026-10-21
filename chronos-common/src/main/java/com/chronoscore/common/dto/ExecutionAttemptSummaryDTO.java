package com.chronoscore.common.dto;

import com.chronoscore.common.enums.ExecutionAttemptStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Data Transfer Object summarizing a single task execution attempt.
 */
public record ExecutionAttemptSummaryDTO(
        UUID attemptId,
        UUID taskId,
        String workerId,
        int attemptNumber,
        ExecutionAttemptStatus status,
        Instant startedAt,
        Instant completedAt,
        String errorCode
) {
}
