package com.chronoscore.coordinator.recovery.dlq;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.exception.TaskNotFoundException;
import com.chronoscore.coordinator.persistence.model.OutboxEvent;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.repository.TaskAuditRepository;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.recovery.lease.ExecutionLeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DlqServiceTest {

    private TaskRepository taskRepository;
    private TaskAuditRepository auditRepository;
    private OutboxRepository outboxRepository;
    private ExecutionLeaseService leaseService;
    private DlqService dlqService;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        auditRepository = mock(TaskAuditRepository.class);
        outboxRepository = mock(OutboxRepository.class);
        leaseService = mock(ExecutionLeaseService.class);

        dlqService = new DlqService(taskRepository, auditRepository, outboxRepository, leaseService);
    }

    private Task createDlqTask(UUID taskId) {
        return Task.builder()
                .taskId(taskId)
                .tenantId("default-tenant")
                .taskType("PAYMENT")
                .payload("{\"amount\":100}")
                .status(TaskStatus.DLQ)
                .maxAttempts(3)
                .currentAttempt(3)
                .version(5L)
                .createdAt(Instant.now().minus(Duration.ofHours(1)))
                .build();
    }

    @Test
    @DisplayName("replayTask successfully transitions DLQ task to QUEUED and resets attempts")
    void testReplayTaskSuccess() {
        UUID taskId = UUID.randomUUID();
        Task dlqTask = createDlqTask(taskId);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(dlqTask));
        when(taskRepository.update(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        Task replayed = dlqService.replayTask(taskId, "Operator manual replay");

        assertThat(replayed.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(replayed.getCurrentAttempt()).isEqualTo(0);
        assertThat(replayed.getScheduledAt()).isNotNull();

        // Verify task lease was released
        verify(leaseService).releaseLease(taskId);

        // Verify audit record was appended
        ArgumentCaptor<TaskAuditRecord> auditCaptor = ArgumentCaptor.forClass(TaskAuditRecord.class);
        verify(auditRepository).append(auditCaptor.capture());
        TaskAuditRecord audit = auditCaptor.getValue();
        assertThat(audit.getTaskId()).isEqualTo(taskId);
        assertThat(audit.getFromStatus()).isEqualTo(TaskStatus.DLQ);
        assertThat(audit.getToStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(audit.getReason()).contains("Operator manual replay");

        // Verify outbox event was appended
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).append(outboxCaptor.capture());
        OutboxEvent outbox = outboxCaptor.getValue();
        assertThat(outbox.getAggregateId()).isEqualTo(taskId.toString());
        assertThat(outbox.getEventType()).isEqualTo("TaskReplayed");
    }

    @Test
    @DisplayName("replayTask throws IllegalStateException when task is not in DLQ")
    void testReplayNonDlqTaskFails() {
        UUID taskId = UUID.randomUUID();
        Task runningTask = createDlqTask(taskId).toBuilder().status(TaskStatus.RUNNING).build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(runningTask));

        assertThatThrownBy(() -> dlqService.replayTask(taskId, "manual"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot replay task");

        verify(taskRepository, never()).update(any());
        verify(auditRepository, never()).append(any());
        verify(outboxRepository, never()).append(any());
    }

    @Test
    @DisplayName("replayTask throws TaskNotFoundException when task is not found")
    void testReplayNonExistentTaskFails() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dlqService.replayTask(taskId, "manual"))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    @DisplayName("listDlqTasks delegates to repository findByStatus with DLQ status")
    void testListDlqTasks() {
        Task t1 = createDlqTask(UUID.randomUUID());
        when(taskRepository.findByStatus(TaskStatus.DLQ, 10)).thenReturn(List.of(t1));

        List<Task> results = dlqService.listDlqTasks(10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTaskId()).isEqualTo(t1.getTaskId());
    }
}
