package com.chronoscore.common.domain;

import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.exceptions.InvalidTaskTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskTest {

    @Test
    @DisplayName("Should create task with valid defaults and explicit parameters")
    void testValidTaskCreation() {
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant scheduled = now.plusSeconds(60);
        Instant deadline = now.plusSeconds(300);

        Task task = Task.builder()
                .taskId(taskId)
                .taskType("INGESTION_PIPELINE")
                .payload("{\"source\":\"s3://bucket/data.csv\"}")
                .priority(TaskPriority.HIGH)
                .status(TaskStatus.CREATED)
                .tenantId("tenant-alpha")
                .idempotencyKey("idem-001")
                .createdAt(now)
                .scheduledAt(scheduled)
                .deadline(deadline)
                .timeout(Duration.ofMinutes(5))
                .maxAttempts(4)
                .currentAttempt(0)
                .version(1L)
                .build();

        assertThat(task.getTaskId()).isEqualTo(taskId);
        assertThat(task.getTaskType()).isEqualTo("INGESTION_PIPELINE");
        assertThat(task.getPayload()).isEqualTo("{\"source\":\"s3://bucket/data.csv\"}");
        assertThat(task.getPriority()).isEqualTo(TaskPriority.HIGH);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CREATED);
        assertThat(task.getTenantId()).isEqualTo("tenant-alpha");
        assertThat(task.getIdempotencyKey()).isEqualTo("idem-001");
        assertThat(task.getCreatedAt()).isEqualTo(now);
        assertThat(task.getScheduledAt()).isEqualTo(scheduled);
        assertThat(task.getDeadline()).isEqualTo(deadline);
        assertThat(task.getTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(task.getMaxAttempts()).isEqualTo(4);
        assertThat(task.getCurrentAttempt()).isZero();
        assertThat(task.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should populate sensible defaults when optional fields are omitted")
    void testTaskCreationDefaults() {
        UUID taskId = UUID.randomUUID();
        Task task = Task.builder()
                .taskId(taskId)
                .taskType("DEFAULT_TASK")
                .payload("{}")
                .tenantId("tenant-default")
                .build();

        assertThat(task.getPriority()).isEqualTo(TaskPriority.NORMAL);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CREATED);
        assertThat(task.getMaxAttempts()).isEqualTo(3);
        assertThat(task.getCurrentAttempt()).isZero();
        assertThat(task.getVersion()).isZero();
        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getScheduledAt()).isNull();
        assertThat(task.getDeadline()).isNull();
        assertThat(task.getTimeout()).isNull();
        assertThat(task.getIdempotencyKey()).isNull();
    }

    @Test
    @DisplayName("withStatus should advance status and increment version")
    void testWithStatusSuccess() {
        UUID taskId = UUID.randomUUID();
        Task task = Task.builder()
                .taskId(taskId)
                .taskType("SAMPLE")
                .payload("{}")
                .tenantId("tenant-1")
                .status(TaskStatus.CREATED)
                .version(0L)
                .build();

        Task queued = task.withStatus(TaskStatus.QUEUED);
        assertThat(queued.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(queued.getVersion()).isEqualTo(1L);
        assertThat(queued.getTaskId()).isEqualTo(taskId);

        Task dispatched = queued.withStatus(TaskStatus.DISPATCHED);
        assertThat(dispatched.getStatus()).isEqualTo(TaskStatus.DISPATCHED);
        assertThat(dispatched.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("withStatus should reject invalid transitions and leave version untouched")
    void testWithStatusRejectsInvalidTransition() {
        Task task = Task.builder()
                .taskId(UUID.randomUUID())
                .taskType("SAMPLE")
                .payload("{}")
                .tenantId("tenant-1")
                .status(TaskStatus.CREATED)
                .version(0L)
                .build();

        assertThatThrownBy(() -> task.withStatus(TaskStatus.RUNNING))
                .isInstanceOf(InvalidTaskTransitionException.class)
                .hasMessageContaining("cannot transition from CREATED to RUNNING");
    }

    @Test
    @DisplayName("withIncrementedAttempt should bump currentAttempt and version")
    void testWithIncrementedAttempt() {
        Task task = Task.builder()
                .taskId(UUID.randomUUID())
                .taskType("SAMPLE")
                .payload("{}")
                .tenantId("tenant-1")
                .currentAttempt(0)
                .version(2L)
                .build();

        Task updated = task.withIncrementedAttempt();
        assertThat(updated.getCurrentAttempt()).isEqualTo(1);
        assertThat(updated.getVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Task equality should be based purely on taskId")
    void testTaskEquality() {
        UUID taskId = UUID.randomUUID();
        Task task1 = Task.builder()
                .taskId(taskId)
                .taskType("TASK_A")
                .payload("payloadA")
                .tenantId("tenant-1")
                .build();

        Task task2 = Task.builder()
                .taskId(taskId)
                .taskType("TASK_B")
                .payload("payloadB")
                .tenantId("tenant-2")
                .build();

        Task task3 = Task.builder()
                .taskId(UUID.randomUUID())
                .taskType("TASK_A")
                .payload("payloadA")
                .tenantId("tenant-1")
                .build();

        assertThat(task1).isEqualTo(task2);
        assertThat(task1.hashCode()).isEqualTo(task2.hashCode());
        assertThat(task1).isNotEqualTo(task3);
    }
}
