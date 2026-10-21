package com.chronoscore.coordinator.recovery;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.proto.ProtoExecutionStatus;
import com.chronoscore.common.proto.ProtoTaskResult;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import com.chronoscore.coordinator.coordination.lock.DistributedLockService;
import com.chronoscore.coordinator.coordination.lock.LockHandle;
import com.chronoscore.coordinator.persistence.model.OutboxEvent;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;
import com.chronoscore.coordinator.persistence.repository.ExecutionAttemptRepository;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.repository.TaskAuditRepository;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.recovery.config.RetryProperties;
import com.chronoscore.coordinator.recovery.lease.ExecutionLeaseService;
import com.chronoscore.coordinator.recovery.lease.TaskExecutionLease;
import com.chronoscore.coordinator.recovery.policy.DefaultRetryPolicy;
import com.chronoscore.coordinator.recovery.policy.JitterStrategy;
import com.chronoscore.coordinator.recovery.policy.RetryPolicy;
import com.chronoscore.coordinator.recovery.timeout.TaskTimeoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskRecoveryServiceTest {

    private TaskRepository taskRepository;
    private ExecutionAttemptRepository attemptRepository;
    private TaskAuditRepository auditRepository;
    private OutboxRepository outboxRepository;
    private ExecutionLeaseService leaseService;
    private RetryPolicy retryPolicy;
    private WorkerRegistry workerRegistry;
    private TaskTimeoutService timeoutService;
    private DistributedLockService lockService;

    private TaskRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        attemptRepository = mock(ExecutionAttemptRepository.class);
        auditRepository = mock(TaskAuditRepository.class);
        outboxRepository = mock(OutboxRepository.class);
        leaseService = mock(ExecutionLeaseService.class);
        workerRegistry = mock(WorkerRegistry.class);
        timeoutService = mock(TaskTimeoutService.class);
        lockService = mock(DistributedLockService.class);

        RetryProperties retryProperties = new RetryProperties();
        retryProperties.setEnabled(true);
        retryProperties.setBaseDelay(Duration.ofSeconds(1));
        retryProperties.setMaxDelay(Duration.ofSeconds(60));
        retryProperties.setMultiplier(2.0);
        retryProperties.setJitter(false);
        retryPolicy = new DefaultRetryPolicy(retryProperties, JitterStrategy.none());

        when(lockService.tryAcquire(anyString(), any(Duration.class)))
                .thenAnswer(inv -> Optional.of(new LockHandle(inv.getArgument(0), "test-owner", Instant.now(), inv.getArgument(1))));
        when(lockService.release(any(LockHandle.class))).thenReturn(true);

        recoveryService = new TaskRecoveryService(
                taskRepository,
                attemptRepository,
                auditRepository,
                outboxRepository,
                leaseService,
                retryPolicy,
                workerRegistry,
                timeoutService,
                lockService
        );
    }

    private Task createRunningTask(UUID taskId, int currentAttempt, int maxAttempts) {
        return Task.builder()
                .taskId(taskId)
                .tenantId("default-tenant")
                .taskType("COMPUTE")
                .payload("{}")
                .status(TaskStatus.RUNNING)
                .currentAttempt(currentAttempt)
                .maxAttempts(maxAttempts)
                .version(1L)
                .createdAt(Instant.now().minus(Duration.ofMinutes(1)))
                .build();
    }

    private ExecutionAttempt createAttempt(UUID taskId, UUID attemptId, String workerId, int attemptNumber) {
        return ExecutionAttempt.builder()
                .attemptId(attemptId)
                .taskId(taskId)
                .workerId(workerId)
                .attemptNumber(attemptNumber)
                .status(ExecutionAttemptStatus.RUNNING)
                .startedAt(Instant.now().minus(Duration.ofSeconds(5)))
                .build();
    }

    @Test
    @DisplayName("Normal successful task execution completes attempt and transitions task to SUCCEEDED")
    void testNormalSuccessResult() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerId = "worker-1";

        Task task = createRunningTask(taskId, 1, 3);
        ExecutionAttempt attempt = createAttempt(taskId, attemptId, workerId, 1);
        TaskExecutionLease lease = TaskExecutionLease.builder()
                .taskId(taskId)
                .attemptId(attemptId)
                .workerId(workerId)
                .attemptNumber(1)
                .leaseAcquiredAt(Instant.now().minusSeconds(10))
                .leaseExpiresAt(Instant.now().plusSeconds(20))
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.update(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.findByTaskIdAndAttemptNumber(taskId, 1)).thenReturn(Optional.of(attempt));
        when(attemptRepository.update(any(ExecutionAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(leaseService.getLease(taskId)).thenReturn(Optional.of(lease));

        ProtoTaskResult result = ProtoTaskResult.newBuilder()
                .setTaskId(taskId.toString())
                .setWorkerId(workerId)
                .setAttemptNumber(1)
                .setStatus(ProtoExecutionStatus.EXECUTION_STATUS_SUCCEEDED)
                .build();

        boolean accepted = recoveryService.handleTaskResult(result);
        assertThat(accepted).isTrue();

        // Verify task updated to SUCCEEDED
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).update(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskStatus.SUCCEEDED);

        // Verify attempt updated to SUCCEEDED
        ArgumentCaptor<ExecutionAttempt> attemptCaptor = ArgumentCaptor.forClass(ExecutionAttempt.class);
        verify(attemptRepository).update(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);

        // Verify lease released
        verify(leaseService).releaseLease(taskId);
    }

    @Test
    @DisplayName("Retryable failure schedules retry with exponential backoff and transitions task back to QUEUED")
    void testRetryableFailureSchedulesRetry() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerId = "worker-1";

        Task task = createRunningTask(taskId, 1, 3);
        ExecutionAttempt attempt = createAttempt(taskId, attemptId, workerId, 1);
        TaskExecutionLease lease = TaskExecutionLease.builder()
                .taskId(taskId)
                .attemptId(attemptId)
                .workerId(workerId)
                .attemptNumber(1)
                .leaseAcquiredAt(Instant.now().minusSeconds(10))
                .leaseExpiresAt(Instant.now().plusSeconds(20))
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.update(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.findByTaskIdAndAttemptNumber(taskId, 1)).thenReturn(Optional.of(attempt));
        when(attemptRepository.update(any(ExecutionAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(leaseService.getLease(taskId)).thenReturn(Optional.of(lease));

        ProtoTaskResult result = ProtoTaskResult.newBuilder()
                .setTaskId(taskId.toString())
                .setWorkerId(workerId)
                .setAttemptNumber(1)
                .setStatus(ProtoExecutionStatus.EXECUTION_STATUS_FAILED)
                .setErrorCode("TEMPORARY_NETWORK_FAILURE")
                .setErrorMessage("Connection reset by peer")
                .build();

        boolean accepted = recoveryService.handleTaskResult(result);
        assertThat(accepted).isTrue();

        // Verify task updated to QUEUED with future scheduledAt (baseDelay = 1s)
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).update(taskCaptor.capture());
        Task updatedTask = taskCaptor.getValue();
        assertThat(updatedTask.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(updatedTask.getScheduledAt()).isAfterOrEqualTo(Instant.now().plusMillis(500));

        // Verify attempt updated to FAILED
        ArgumentCaptor<ExecutionAttempt> attemptCaptor = ArgumentCaptor.forClass(ExecutionAttempt.class);
        verify(attemptRepository).update(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
        assertThat(attemptCaptor.getValue().getErrorCode()).isEqualTo("TEMPORARY_NETWORK_FAILURE");

        // Verify lease released
        verify(leaseService).releaseLease(taskId);
    }

    @Test
    @DisplayName("Max attempts reached moves task directly to DLQ")
    void testMaxAttemptsReachedMovesToDlq() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerId = "worker-1";

        Task task = createRunningTask(taskId, 3, 3); // Current attempt 3 of 3
        ExecutionAttempt attempt = createAttempt(taskId, attemptId, workerId, 3);
        TaskExecutionLease lease = TaskExecutionLease.builder()
                .taskId(taskId)
                .attemptId(attemptId)
                .workerId(workerId)
                .attemptNumber(3)
                .leaseAcquiredAt(Instant.now().minusSeconds(10))
                .leaseExpiresAt(Instant.now().plusSeconds(20))
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.update(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.findByTaskIdAndAttemptNumber(taskId, 3)).thenReturn(Optional.of(attempt));
        when(attemptRepository.update(any(ExecutionAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(leaseService.getLease(taskId)).thenReturn(Optional.of(lease));

        ProtoTaskResult result = ProtoTaskResult.newBuilder()
                .setTaskId(taskId.toString())
                .setWorkerId(workerId)
                .setAttemptNumber(3)
                .setStatus(ProtoExecutionStatus.EXECUTION_STATUS_FAILED)
                .setErrorCode("WORKER_TIMEOUT")
                .setErrorMessage("Timeout exceeded")
                .build();

        boolean accepted = recoveryService.handleTaskResult(result);
        assertThat(accepted).isTrue();

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).update(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskStatus.DLQ);
    }

    @Test
    @DisplayName("Non-retryable failure moves task directly to DLQ on attempt 1")
    void testNonRetryableFailureMovesToDlq() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerId = "worker-1";

        Task task = createRunningTask(taskId, 1, 5); // Attempt 1 of 5
        ExecutionAttempt attempt = createAttempt(taskId, attemptId, workerId, 1);
        TaskExecutionLease lease = TaskExecutionLease.builder()
                .taskId(taskId)
                .attemptId(attemptId)
                .workerId(workerId)
                .attemptNumber(1)
                .leaseAcquiredAt(Instant.now().minusSeconds(10))
                .leaseExpiresAt(Instant.now().plusSeconds(20))
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.update(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.findByTaskIdAndAttemptNumber(taskId, 1)).thenReturn(Optional.of(attempt));
        when(attemptRepository.update(any(ExecutionAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(leaseService.getLease(taskId)).thenReturn(Optional.of(lease));

        ProtoTaskResult result = ProtoTaskResult.newBuilder()
                .setTaskId(taskId.toString())
                .setWorkerId(workerId)
                .setAttemptNumber(1)
                .setStatus(ProtoExecutionStatus.EXECUTION_STATUS_FAILED)
                .setErrorCode("VALIDATION_ERROR")
                .setErrorMessage("Payload malformed")
                .build();

        boolean accepted = recoveryService.handleTaskResult(result);
        assertThat(accepted).isTrue();

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).update(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskStatus.DLQ);
    }

    /**
     * MANDATORY TEST (Requirement 19 & 41):
     * Attempt 1 -> Worker A
     * Worker A becomes stale / partitioned
     * Attempt 2 -> Worker B succeeds
     * Worker A later reports success for Attempt 1
     * Expected: Worker B result accepted, Worker A stale result rejected, Task remains SUCCEEDED.
     */
    @Test
    @DisplayName("MANDATORY TEST: Stale result from old attempt/partitioned worker is rejected when newer attempt succeeded")
    void testStaleResultRejectedWhenNewerAttemptSucceeded() {
        UUID taskId = UUID.randomUUID();
        String workerA = "worker-A";
        String workerB = "worker-B";

        // Task has already been completed by Attempt 2 on Worker B and is SUCCEEDED
        Task taskAfterAttempt2 = Task.builder()
                .taskId(taskId)
                .tenantId("default-tenant")
                .taskType("COMPUTE")
                .payload("{}")
                .status(TaskStatus.SUCCEEDED)
                .currentAttempt(2)
                .maxAttempts(3)
                .version(3L)
                .createdAt(Instant.now().minus(Duration.ofMinutes(2)))
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(taskAfterAttempt2));

        // Worker A reports success for Attempt 1
        ProtoTaskResult staleResultWorkerA = ProtoTaskResult.newBuilder()
                .setTaskId(taskId.toString())
                .setWorkerId(workerA)
                .setAttemptNumber(1)
                .setStatus(ProtoExecutionStatus.EXECUTION_STATUS_SUCCEEDED)
                .build();

        boolean accepted = recoveryService.handleTaskResult(staleResultWorkerA);

        // Worker A's stale report must be REJECTED
        assertThat(accepted).isFalse();

        // Task repository must NOT be updated - state must remain SUCCEEDED
        verify(taskRepository, never()).update(any());

        // Audit log must record STALE_RESULT_REJECTED
        ArgumentCaptor<TaskAuditRecord> auditCaptor = ArgumentCaptor.forClass(TaskAuditRecord.class);
        verify(auditRepository).append(auditCaptor.capture());
        TaskAuditRecord audit = auditCaptor.getValue();
        assertThat(audit.getTaskId()).isEqualTo(taskId);
        assertThat(audit.getReason()).contains("STALE_RESULT_REJECTED");
        assertThat(audit.getMetadata()).contains("workerId=worker-A");
        assertThat(audit.getMetadata()).contains("attemptNumber=1");
    }

    @Test
    @DisplayName("Stale result rejected when reported attempt number is strictly less than active attempt number")
    void testStaleResultRejectedWhenAttemptNumberLessThanCurrent() {
        UUID taskId = UUID.randomUUID();
        String workerA = "worker-A";

        // Task is actively on Attempt 2 (RUNNING)
        Task activeTaskAttempt2 = createRunningTask(taskId, 2, 3);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(activeTaskAttempt2));

        // Worker A reports result for Attempt 1
        ProtoTaskResult staleResult = ProtoTaskResult.newBuilder()
                .setTaskId(taskId.toString())
                .setWorkerId(workerA)
                .setAttemptNumber(1)
                .setStatus(ProtoExecutionStatus.EXECUTION_STATUS_SUCCEEDED)
                .build();

        boolean accepted = recoveryService.handleTaskResult(staleResult);
        assertThat(accepted).isFalse();

        verify(taskRepository, never()).update(any());
        verify(auditRepository).append(argThat(a -> a.getReason().contains("STALE_RESULT_REJECTED")));
    }

    @Test
    @DisplayName("recoverWorkerFailure recovers active orphaned tasks when worker is evicted")
    void testRecoverWorkerFailure() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerA = "worker-A";

        Task task = createRunningTask(taskId, 1, 3);
        ExecutionAttempt attempt = createAttempt(taskId, attemptId, workerA, 1);
        TaskExecutionLease lease = TaskExecutionLease.builder()
                .taskId(taskId)
                .attemptId(attemptId)
                .workerId(workerA)
                .attemptNumber(1)
                .leaseAcquiredAt(Instant.now().minusSeconds(10))
                .leaseExpiresAt(Instant.now().plusSeconds(20))
                .build();

        when(leaseService.getLeasesForWorker(workerA)).thenReturn(List.of(lease));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.update(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.findByTaskIdAndAttemptNumber(taskId, 1)).thenReturn(Optional.of(attempt));
        when(attemptRepository.update(any(ExecutionAttempt.class))).thenAnswer(inv -> inv.getArgument(0));

        int recoveredCount = recoveryService.recoverWorkerFailure(workerA);
        assertThat(recoveredCount).isEqualTo(1);

        // Verify task requeued for retry
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).update(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskStatus.QUEUED);

        // Verify attempt failed with WORKER_UNAVAILABLE
        ArgumentCaptor<ExecutionAttempt> attemptCaptor = ArgumentCaptor.forClass(ExecutionAttempt.class);
        verify(attemptRepository).update(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
        assertThat(attemptCaptor.getValue().getErrorCode()).isEqualTo("WORKER_UNAVAILABLE");

        // Verify lease deleted
        verify(leaseService).releaseLease(taskId);
    }

    @Test
    @DisplayName("recoverExpiredLeases reclaims tasks whose leases expired without renewal")
    void testRecoverExpiredLeases() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerA = "worker-A";

        Task task = createRunningTask(taskId, 1, 3);
        ExecutionAttempt attempt = createAttempt(taskId, attemptId, workerA, 1);
        TaskExecutionLease expiredLease = TaskExecutionLease.builder()
                .taskId(taskId)
                .attemptId(attemptId)
                .workerId(workerA)
                .attemptNumber(1)
                .leaseAcquiredAt(Instant.now().minusSeconds(60))
                .leaseExpiresAt(Instant.now().minusSeconds(10))
                .build();

        when(leaseService.getExpiredLeases(any(Instant.class), anyInt())).thenReturn(List.of(expiredLease));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.update(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.findByTaskIdAndAttemptNumber(taskId, 1)).thenReturn(Optional.of(attempt));
        when(attemptRepository.update(any(ExecutionAttempt.class))).thenAnswer(inv -> inv.getArgument(0));

        int recoveredCount = recoveryService.recoverExpiredLeases(Instant.now(), 50);
        assertThat(recoveredCount).isEqualTo(1);

        // Task requeued for next attempt
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).update(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskStatus.QUEUED);

        // Attempt marked FAILED with LEASE_EXPIRED
        verify(attemptRepository).update(argThat(a -> "LEASE_EXPIRED".equals(a.getErrorCode())));
        verify(leaseService).releaseLease(taskId);
    }
}
