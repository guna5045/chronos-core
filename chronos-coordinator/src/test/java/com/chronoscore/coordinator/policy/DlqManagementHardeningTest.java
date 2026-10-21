package com.chronoscore.coordinator.policy;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.repository.ExecutionAttemptRepository;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.repository.TaskAuditRepository;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.recovery.dlq.DlqService;
import com.chronoscore.coordinator.recovery.dlq.DlqTaskInspection;
import com.chronoscore.coordinator.recovery.lease.ExecutionLeaseService;
import com.chronoscore.coordinator.workflow.service.WorkflowExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test suite verifying DLQ management hardening: inspect, archive, safe replay,
 * and workflow DAG reactivation (Phase 09 Section 29, 30, 31, 32, 44).
 */
@ExtendWith(MockitoExtension.class)
class DlqManagementHardeningTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskAuditRepository auditRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private ExecutionLeaseService leaseService;

    @Mock
    private ExecutionAttemptRepository attemptRepository;

    @Mock
    private WorkflowExecutionService workflowExecutionService;

    private DlqService dlqService;

    @BeforeEach
    void setUp() {
        dlqService = new DlqService(
                taskRepository,
                auditRepository,
                outboxRepository,
                leaseService,
                attemptRepository,
                workflowExecutionService
        );
    }

    private Task createDlqTask() {
        return Task.builder()
                .taskId(UUID.randomUUID())
                .tenantId("tenant-dlq")
                .taskType("PAYMENT")
                .payload("{\"orderId\":\"12345\"}")
                .priority(TaskPriority.HIGH)
                .status(TaskStatus.DLQ)
                .currentAttempt(3)
                .maxAttempts(3)
                .createdAt(Instant.now().minusSeconds(100))
                .build();
    }

    @Test
    @DisplayName("DLQ Inspect: Returns comprehensive diagnostic task inspection with failure history")
    void testInspectDlqTask() {
        Task task = createDlqTask();
        ExecutionAttempt failedAttempt = ExecutionAttempt.builder()
                .attemptId(UUID.randomUUID())
                .taskId(task.getTaskId())
                .workerId("worker-dlq-1")
                .attemptNumber(3)
                .status(ExecutionAttemptStatus.FAILED)
                .errorCode("GATEWAY_TIMEOUT")
                .errorMessage("Payment gateway timed out after 30s")
                .startedAt(Instant.now().minusSeconds(35))
                .completedAt(Instant.now().minusSeconds(5))
                .build();

        when(taskRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        when(attemptRepository.findByTaskId(task.getTaskId())).thenReturn(List.of(failedAttempt));
        when(auditRepository.findByTaskId(task.getTaskId())).thenReturn(Collections.emptyList());

        DlqTaskInspection inspection = dlqService.inspectTask(task.getTaskId()).orElseThrow();

        assertThat(inspection.task()).isEqualTo(task);
        assertThat(inspection.attempts()).hasSize(1);
        assertThat(inspection.attempts().get(0).getErrorCode()).isEqualTo("GATEWAY_TIMEOUT");
        assertThat(inspection.attempts().get(0).getErrorMessage()).contains("Payment gateway timed out");
    }

    @Test
    @DisplayName("DLQ Archive: Records durable archival marker with audit trail and outbox event")
    void testArchiveDlqTask() {
        Task task = createDlqTask();
        when(taskRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));

        Task archived = dlqService.archiveTask(task.getTaskId(), "Irrecoverable payload discard");

        assertThat(archived.getStatus()).isEqualTo(TaskStatus.DLQ);
        verify(leaseService).releaseLease(task.getTaskId());
        verify(auditRepository).append(any());
        verify(outboxRepository).append(any());
    }

    @Test
    @DisplayName("DLQ Replay: Re-queues task, resets attempt count, and notifies workflow engine")
    void testReplayDlqTaskWithWorkflowReactivation() {
        Task task = createDlqTask();
        when(taskRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        when(taskRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        Task replayed = dlqService.replayTask(task.getTaskId(), "Operator replayed after fixing config");

        assertThat(replayed.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(replayed.getCurrentAttempt()).isEqualTo(0);

        // Verify lease cleaned up and workflow reactivation triggered
        verify(leaseService).releaseLease(task.getTaskId());
        verify(workflowExecutionService).onTaskReplayed(task.getTaskId());
    }

    @Test
    @DisplayName("DLQ Replay: Replaying a non-DLQ task throws IllegalStateException")
    void testReplayNonDlqTaskRejected() {
        Task runningTask = Task.builder()
                .taskId(UUID.randomUUID())
                .tenantId("tenant-dlq")
                .taskType("PAYMENT")
                .payload("{}")
                .status(TaskStatus.RUNNING)
                .build();

        when(taskRepository.findById(runningTask.getTaskId())).thenReturn(Optional.of(runningTask));

        assertThatThrownBy(() -> dlqService.replayTask(runningTask.getTaskId(), "Invalid replay"))
                .isInstanceOf(IllegalStateException.class);
    }
}
