package com.chronoscore.coordinator.policy;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.exceptions.InvalidTaskTransitionException;
import com.chronoscore.common.proto.ProtoExecutionStatus;
import com.chronoscore.common.proto.ProtoTaskResult;
import com.chronoscore.coordinator.persistence.repository.ExecutionAttemptRepository;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.repository.TaskAuditRepository;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import com.chronoscore.coordinator.recovery.TaskRecoveryService;
import com.chronoscore.coordinator.recovery.lease.ExecutionLeaseService;
import com.chronoscore.coordinator.recovery.policy.RetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test suite verifying cancellation hardening across all lifecycle stages
 * and race conditions (cancel + worker start, cancel + task completion, cancel + retry).
 * (Phase 09 Section 23, 24, 25, 43).
 */
@ExtendWith(MockitoExtension.class)
class TaskCancellationHardeningTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ExecutionAttemptRepository attemptRepository;

    @Mock
    private TaskAuditRepository auditRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private TenantConcurrencyManager concurrencyManager;

    @Mock
    private ExecutionLeaseService leaseService;

    @Mock
    private RetryPolicy retryPolicy;

    private TransactionalTaskService taskService;
    private TaskRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        taskService = new TransactionalTaskService(taskRepository, attemptRepository, auditRepository, outboxRepository);
        taskService.setConcurrencyManager(concurrencyManager);

        recoveryService = new TaskRecoveryService(
                taskRepository,
                attemptRepository,
                auditRepository,
                outboxRepository,
                leaseService,
                retryPolicy,
                org.mockito.Mockito.mock(com.chronoscore.coordinator.cluster.registry.WorkerRegistry.class),
                org.mockito.Mockito.mock(com.chronoscore.coordinator.recovery.timeout.TaskTimeoutService.class),
                org.mockito.Mockito.mock(com.chronoscore.coordinator.coordination.lock.DistributedLockService.class)
        );
        recoveryService.setConcurrencyManager(concurrencyManager);
    }

    private Task createMockTask(TaskStatus status) {
        return Task.builder()
                .taskId(UUID.randomUUID())
                .tenantId("tenant-cancel")
                .taskType("CANCEL_TEST")
                .payload("{}")
                .status(status)
                .priority(TaskPriority.HIGH)
                .currentAttempt(1)
                .maxAttempts(3)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Cancellation: Successfully cancels task in CREATED, QUEUED, DISPATCHED, RUNNING, and retry-waiting")
    void testCancellationAcrossLifecycleStages() {
        for (TaskStatus initialStatus : new TaskStatus[]{
                TaskStatus.CREATED,
                TaskStatus.QUEUED,
                TaskStatus.DISPATCHED,
                TaskStatus.RUNNING
        }) {
            Task task = createMockTask(initialStatus);
            when(taskRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));
            when(taskRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

            Task cancelled = taskService.cancelTask(task.getTaskId(), "Operator cancellation");

            assertThat(cancelled.getStatus())
                    .as("Task in status %s should successfully cancel", initialStatus)
                    .isEqualTo(TaskStatus.CANCELLED);

            verify(concurrencyManager).releaseConcurrency(task.getTenantId(), task.getTaskId());
        }

        // Test retry-waiting task (QUEUED with future scheduledAt)
        Task retryWaitingTask = createMockTask(TaskStatus.QUEUED).toBuilder()
                .currentAttempt(1)
                .scheduledAt(Instant.now().plusSeconds(60))
                .build();
        when(taskRepository.findById(retryWaitingTask.getTaskId())).thenReturn(Optional.of(retryWaitingTask));
        Task cancelledRetry = taskService.cancelTask(retryWaitingTask.getTaskId(), "Cancel retry-waiting task");
        assertThat(cancelledRetry.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("Cancellation: Idempotent cancellation on already CANCELLED task")
    void testIdempotentCancellation() {
        Task task = createMockTask(TaskStatus.CANCELLED);
        when(taskRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));

        Task result = taskService.cancelTask(task.getTaskId(), "Redundant cancel");
        assertThat(result.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("Cancellation: Reject cancellation on terminal SUCCEEDED task")
    void testCancelTerminalSucceededRejected() {
        Task task = createMockTask(TaskStatus.SUCCEEDED);
        when(taskRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.cancelTask(task.getTaskId(), "Late cancel"))
                .isInstanceOf(InvalidTaskTransitionException.class);
    }

    @Test
    @DisplayName("Cancellation Race: Worker start on already CANCELLED task is rejected")
    void testCancelRaceWorkerStartRejected() {
        Task task = createMockTask(TaskStatus.CANCELLED);
        when(taskRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.startExecutionAttempt(task.getTaskId(), "worker-1", 1))
                .isInstanceOf(InvalidTaskTransitionException.class);
    }

    @Test
    @DisplayName("Cancellation Race: Late worker completion does NOT resurrect CANCELLED task")
    void testCancelRaceWorkerCompletionDoesNotResurrect() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        Task cancelledTask = Task.builder()
                .taskId(taskId)
                .tenantId("tenant-cancel")
                .taskType("CANCEL_TEST")
                .payload("{}")
                .status(TaskStatus.CANCELLED)
                .currentAttempt(1)
                .maxAttempts(3)
                .build();

        ExecutionAttempt runningAttempt = ExecutionAttempt.builder()
                .attemptId(attemptId)
                .taskId(taskId)
                .workerId("worker-1")
                .attemptNumber(1)
                .status(ExecutionAttemptStatus.RUNNING)
                .startedAt(Instant.now().minusSeconds(10))
                .build();

        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(runningAttempt));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(cancelledTask));

        ExecutionAttempt resultAttempt = taskService.completeExecutionAttempt(attemptId, true, null, null);

        assertThat(resultAttempt.getStatus()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);
        // Crucial invariant: task state was NOT updated to SUCCEEDED; task remains CANCELLED!
        assertThat(cancelledTask.getStatus()).isEqualTo(TaskStatus.CANCELLED);

        // Verify concurrency slot released
        verify(concurrencyManager).releaseConcurrency("tenant-cancel", taskId);
    }

    @Test
    @DisplayName("Cancellation Race: TaskRecoveryService rejects worker result for CANCELLED task")
    void testRecoveryServiceRejectsResultForCancelledTask() {
        UUID taskId = UUID.randomUUID();
        Task cancelledTask = Task.builder()
                .taskId(taskId)
                .tenantId("tenant-cancel")
                .taskType("CANCEL_TEST")
                .payload("{}")
                .status(TaskStatus.CANCELLED)
                .currentAttempt(1)
                .maxAttempts(3)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(cancelledTask));

        ProtoTaskResult result = ProtoTaskResult.newBuilder()
                .setTaskId(taskId.toString())
                .setWorkerId("worker-1")
                .setAttemptNumber(1)
                .setStatus(ProtoExecutionStatus.EXECUTION_STATUS_SUCCEEDED)
                .build();

        boolean accepted = recoveryService.handleTaskResult(result);
        assertThat(accepted).isFalse();
    }
}
