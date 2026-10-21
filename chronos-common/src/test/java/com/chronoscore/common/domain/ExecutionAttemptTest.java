package com.chronoscore.common.domain;

import com.chronoscore.common.enums.ExecutionAttemptStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionAttemptTest {

    @Test
    @DisplayName("Should construct ExecutionAttempt with valid parameters")
    void testExecutionAttemptCreation() {
        UUID attemptId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant start = Instant.now();

        ExecutionAttempt attempt = ExecutionAttempt.builder()
                .attemptId(attemptId)
                .taskId(taskId)
                .workerId("worker-node-101")
                .attemptNumber(1)
                .status(ExecutionAttemptStatus.RUNNING)
                .startedAt(start)
                .build();

        assertThat(attempt.getAttemptId()).isEqualTo(attemptId);
        assertThat(attempt.getTaskId()).isEqualTo(taskId);
        assertThat(attempt.getWorkerId()).isEqualTo("worker-node-101");
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.RUNNING);
        assertThat(attempt.getStartedAt()).isEqualTo(start);
        assertThat(attempt.getCompletedAt()).isNull();
        assertThat(attempt.getErrorCode()).isNull();
        assertThat(attempt.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("withSuccess should record successful completion")
    void testWithSuccess() {
        ExecutionAttempt attempt = ExecutionAttempt.builder()
                .attemptId(UUID.randomUUID())
                .taskId(UUID.randomUUID())
                .workerId("worker-1")
                .attemptNumber(1)
                .build();

        Instant completedAt = Instant.now();
        ExecutionAttempt succeeded = attempt.withSuccess(completedAt);

        assertThat(succeeded.getStatus()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);
        assertThat(succeeded.getCompletedAt()).isEqualTo(completedAt);
        assertThat(succeeded.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("withFailure should record error details and completed timestamp")
    void testWithFailure() {
        ExecutionAttempt attempt = ExecutionAttempt.builder()
                .attemptId(UUID.randomUUID())
                .taskId(UUID.randomUUID())
                .workerId("worker-1")
                .attemptNumber(2)
                .build();

        Instant completedAt = Instant.now();
        ExecutionAttempt failed = attempt.withFailure("TIMEOUT_EXCEEDED", "Task execution timed out after 300s", completedAt);

        assertThat(failed.getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("TIMEOUT_EXCEEDED");
        assertThat(failed.getErrorMessage()).isEqualTo("Task execution timed out after 300s");
        assertThat(failed.getCompletedAt()).isEqualTo(completedAt);
        assertThat(failed.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("withCancellation should record cancellation message")
    void testWithCancellation() {
        ExecutionAttempt attempt = ExecutionAttempt.builder()
                .attemptId(UUID.randomUUID())
                .taskId(UUID.randomUUID())
                .workerId("worker-1")
                .attemptNumber(1)
                .build();

        Instant completedAt = Instant.now();
        ExecutionAttempt cancelled = attempt.withCancellation("Operator manual abort", completedAt);

        assertThat(cancelled.getStatus()).isEqualTo(ExecutionAttemptStatus.CANCELLED);
        assertThat(cancelled.getErrorMessage()).isEqualTo("Operator manual abort");
        assertThat(cancelled.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    @DisplayName("ExecutionAttempt equality should be based on attemptId")
    void testAttemptEquality() {
        UUID attemptId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        ExecutionAttempt attempt1 = ExecutionAttempt.builder()
                .attemptId(attemptId)
                .taskId(taskId)
                .workerId("worker-1")
                .build();

        ExecutionAttempt attempt2 = ExecutionAttempt.builder()
                .attemptId(attemptId)
                .taskId(taskId)
                .workerId("worker-2")
                .build();

        ExecutionAttempt attempt3 = ExecutionAttempt.builder()
                .attemptId(UUID.randomUUID())
                .taskId(taskId)
                .workerId("worker-1")
                .build();

        assertThat(attempt1).isEqualTo(attempt2);
        assertThat(attempt1.hashCode()).isEqualTo(attempt2.hashCode());
        assertThat(attempt1).isNotEqualTo(attempt3);
    }
}
