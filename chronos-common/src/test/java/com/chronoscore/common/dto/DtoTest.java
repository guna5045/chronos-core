package com.chronoscore.common.dto;

import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DtoTest {

    @Test
    @DisplayName("TaskSubmissionDTO applies default priority and maxAttempts when omitted")
    void testTaskSubmissionDtoDefaults() {
        TaskSubmissionDTO dto = new TaskSubmissionDTO(
                "BATCH_JOB",
                "{}",
                null,
                "tenant-1",
                "idem-key",
                null,
                null,
                null,
                null
        );

        assertThat(dto.priority()).isEqualTo(TaskPriority.NORMAL);
        assertThat(dto.maxAttempts()).isEqualTo(3);
        assertThat(dto.taskType()).isEqualTo("BATCH_JOB");
        assertThat(dto.tenantId()).isEqualTo("tenant-1");
    }

    @Test
    @DisplayName("TaskSummaryDTO correctly maps summary view")
    void testTaskSummaryDto() {
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();

        TaskSummaryDTO dto = new TaskSummaryDTO(
                taskId,
                "REPORT_GEN",
                TaskPriority.HIGH,
                TaskStatus.QUEUED,
                "tenant-2",
                now,
                1,
                5,
                2L
        );

        assertThat(dto.taskId()).isEqualTo(taskId);
        assertThat(dto.taskType()).isEqualTo("REPORT_GEN");
        assertThat(dto.priority()).isEqualTo(TaskPriority.HIGH);
        assertThat(dto.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(dto.currentAttempt()).isEqualTo(1);
        assertThat(dto.maxAttempts()).isEqualTo(5);
        assertThat(dto.version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("TaskStateChangeDTO records transition event details")
    void testTaskStateChangeDto() {
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();

        TaskStateChangeDTO dto = new TaskStateChangeDTO(
                taskId,
                TaskStatus.DISPATCHED,
                TaskStatus.RUNNING,
                1,
                now,
                "Worker acknowledged dispatch"
        );

        assertThat(dto.taskId()).isEqualTo(taskId);
        assertThat(dto.previousStatus()).isEqualTo(TaskStatus.DISPATCHED);
        assertThat(dto.newStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(dto.reason()).isEqualTo("Worker acknowledged dispatch");
    }

    @Test
    @DisplayName("ExecutionAttemptSummaryDTO maps attempt summary correctly")
    void testExecutionAttemptSummaryDto() {
        UUID attemptId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();

        ExecutionAttemptSummaryDTO dto = new ExecutionAttemptSummaryDTO(
                attemptId,
                taskId,
                "worker-42",
                2,
                ExecutionAttemptStatus.SUCCEEDED,
                now.minusSeconds(10),
                now,
                null
        );

        assertThat(dto.attemptId()).isEqualTo(attemptId);
        assertThat(dto.taskId()).isEqualTo(taskId);
        assertThat(dto.workerId()).isEqualTo("worker-42");
        assertThat(dto.attemptNumber()).isEqualTo(2);
        assertThat(dto.status()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);
        assertThat(dto.errorCode()).isNull();
    }
}
