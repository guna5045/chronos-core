package com.chronoscore.worker.execution;

import com.chronoscore.common.proto.ProtoExecutionStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable value object holding the physical execution result of a task attempt executed by a worker.
 */
public record TaskExecutionResult(
        String taskId,
        int attemptNumber,
        ProtoExecutionStatus status,
        String outputPayload,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
) {

    public TaskExecutionResult {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    public static TaskExecutionResult success(String taskId, int attemptNumber, String output, Instant start, Instant end) {
        return new TaskExecutionResult(
                taskId,
                attemptNumber,
                ProtoExecutionStatus.EXECUTION_STATUS_SUCCEEDED,
                output != null ? output : "{}",
                "",
                "",
                start,
                end
        );
    }

    public static TaskExecutionResult failure(String taskId, int attemptNumber, String code, String message, Instant start, Instant end) {
        return new TaskExecutionResult(
                taskId,
                attemptNumber,
                ProtoExecutionStatus.EXECUTION_STATUS_FAILED,
                "{}",
                code != null ? code : "EXECUTION_ERROR",
                message != null ? message : "Execution encountered an error",
                start,
                end
        );
    }

    public static TaskExecutionResult cancelled(String taskId, int attemptNumber, String reason, Instant start, Instant end) {
        return new TaskExecutionResult(
                taskId,
                attemptNumber,
                ProtoExecutionStatus.EXECUTION_STATUS_CANCELLED,
                "{}",
                "TASK_CANCELLED",
                reason != null ? reason : "Cancelled by coordinator request",
                start,
                end
        );
    }
}
