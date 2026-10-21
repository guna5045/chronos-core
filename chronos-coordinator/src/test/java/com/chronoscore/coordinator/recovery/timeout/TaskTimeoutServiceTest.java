package com.chronoscore.coordinator.recovery.timeout;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.cluster.grpc.WorkerDispatchClient;
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
import com.chronoscore.coordinator.recovery.policy.DefaultRetryPolicy;
import com.chronoscore.coordinator.recovery.policy.JitterStrategy;
import com.chronoscore.coordinator.recovery.policy.RetryPolicy;
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

class TaskTimeoutServiceTest {

    private TaskRepository taskRepository;
    private ExecutionAttemptRepository attemptRepository;
    private TaskAuditRepository auditRepository;
    private OutboxRepository outboxRepository;
    private ExecutionLeaseService leaseService;
    private RetryPolicy retryPolicy;
    private WorkerRegistry workerRegistry;
    private WorkerDispatchClient workerDispatchClient;
    private DistributedLockService lockService;

    private TaskTimeoutService timeoutService;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        attemptRepository = mock(ExecutionAttemptRepository.class);
        auditRepository = mock(TaskAuditRepository.class);
        outboxRepository = mock(OutboxRepository.class);
        leaseService = mock(ExecutionLeaseService.class);
        workerRegistry = mock(WorkerRegistry.class);
        workerDispatchClient = mock(WorkerDispatchClient.class);
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

        timeoutService = new TaskTimeoutService(
                taskRepository,
                attemptRepository,
                auditRepository,
                outboxRepository,
                leaseService,
                retryPolicy,
                workerRegistry,
                workerDispatchClient,
                lockService
        );
    }

    private Task createRunningTask(UUID taskId, Duration timeout, int currentAttempt, int maxAttempts) {
        return Task.builder()
                .taskId(taskId)
                .tenantId("default-tenant")
                .taskType("COMPUTE")
                .payload("{}")
                .status(TaskStatus.RUNNING)
                .timeout(timeout)
                .currentAttempt(currentAttempt)
                .maxAttempts(maxAttempts)
                .version(1L)
                .createdAt(Instant.now().minus(Duration.ofMinutes(10)))
                .build();
    }

    private ExecutionAttempt createAttempt(UUID taskId, UUID attemptId, String workerId, int attemptNumber, Duration runningDuration) {
        return ExecutionAttempt.builder()
                .attemptId(attemptId)
                .taskId(taskId)
                .workerId(workerId)
                .attemptNumber(attemptNumber)
                .status(ExecutionAttemptStatus.RUNNING)
                .startedAt(Instant.now().minus(runningDuration))
                .build();
    }

    private WorkerNode createWorker(String workerId) {
        Instant now = Instant.now();
        return new WorkerNode(
                workerId, "localhost", "127.0.0.1", 9091, 8, 1,
                Set.of("COMPUTE"), 1000L, "0.1.0", UUID.randomUUID().toString(),
                WorkerState.ACTIVE, now, now, 0.0, 0.0
        );
    }

    @Test
    @DisplayName("recoverTimedOutTasks detects task exceeding timeout, sends cooperative cancel, and requeues for retry")
    void testTaskTimeoutTriggersCancellationAndRetry() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerId = "worker-1";

        // Task with 30s timeout that has been running for 45s
        Task task = createRunningTask(taskId, Duration.ofSeconds(30), 1, 3);
        ExecutionAttempt attempt = createAttempt(taskId, attemptId, workerId, 1, Duration.ofSeconds(45));
        WorkerNode worker = createWorker(workerId);

        when(taskRepository.findByStatus(TaskStatus.RUNNING, 50)).thenReturn(List.of(task));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.update(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.findLatestAttempt(taskId)).thenReturn(Optional.of(attempt));
        when(attemptRepository.findByTaskIdAndAttemptNumber(taskId, 1)).thenReturn(Optional.of(attempt));
        when(attemptRepository.update(any(ExecutionAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workerRegistry.getWorker(workerId)).thenReturn(Optional.of(worker));
        when(workerDispatchClient.cancelTask(eq(worker), any(), any())).thenReturn(null);

        int timedOutCount = timeoutService.recoverTimedOutTasks(Instant.now(), 50);
        assertThat(timedOutCount).isEqualTo(1);

        // Verify cooperative cancellation requested over gRPC
        verify(workerDispatchClient).cancelTask(eq(worker), argThat(req -> req.getTaskId().equals(taskId.toString())), any());

        // Verify attempt marked FAILED with EXECUTION_TIMEOUT
        ArgumentCaptor<ExecutionAttempt> attemptCaptor = ArgumentCaptor.forClass(ExecutionAttempt.class);
        verify(attemptRepository).update(attemptCaptor.capture());
        ExecutionAttempt updatedAttempt = attemptCaptor.getValue();
        assertThat(updatedAttempt.getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
        assertThat(updatedAttempt.getErrorCode()).isEqualTo("EXECUTION_TIMEOUT");

        // Verify task requeued to QUEUED with backoff
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).update(taskCaptor.capture());
        Task updatedTask = taskCaptor.getValue();
        assertThat(updatedTask.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(updatedTask.getScheduledAt()).isNotNull();

        // Verify lease released
        verify(leaseService).releaseLease(taskId);
    }

    @Test
    @DisplayName("recoverTimedOutTasks ignores task within its timeout window")
    void testTaskWithinTimeoutWindowIgnored() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerId = "worker-1";

        // Task with 60s timeout running for only 10s
        Task task = createRunningTask(taskId, Duration.ofSeconds(60), 1, 3);
        ExecutionAttempt attempt = createAttempt(taskId, attemptId, workerId, 1, Duration.ofSeconds(10));

        when(taskRepository.findByStatus(TaskStatus.RUNNING, 50)).thenReturn(List.of(task));
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(attemptRepository.findLatestAttempt(taskId)).thenReturn(Optional.of(attempt));

        int timedOutCount = timeoutService.recoverTimedOutTasks(Instant.now(), 50);
        assertThat(timedOutCount).isEqualTo(0);

        verify(taskRepository, never()).update(any());
        verify(attemptRepository, never()).update(any());
        verify(workerDispatchClient, never()).cancelTask(any(), any(), any());
    }

    @Test
    @DisplayName("Task timeout exhausts max attempts and transitions task to DLQ")
    void testTaskTimeoutExhaustsMaxAttempts() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String workerId = "worker-1";

        // Current attempt 3 of 3 (maxAttempts = 3)
        Task task = createRunningTask(taskId, Duration.ofSeconds(30), 3, 3);
        ExecutionAttempt attempt = createAttempt(taskId, attemptId, workerId, 3, Duration.ofSeconds(45));
        WorkerNode worker = createWorker(workerId);

        when(taskRepository.findByStatus(TaskStatus.RUNNING, 50)).thenReturn(List.of(task));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.update(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.findLatestAttempt(taskId)).thenReturn(Optional.of(attempt));
        when(attemptRepository.findByTaskIdAndAttemptNumber(taskId, 3)).thenReturn(Optional.of(attempt));
        when(attemptRepository.update(any(ExecutionAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workerRegistry.getWorker(workerId)).thenReturn(Optional.of(worker));

        int timedOutCount = timeoutService.recoverTimedOutTasks(Instant.now(), 50);
        assertThat(timedOutCount).isEqualTo(1);

        // Verify task moved to DLQ
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).update(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskStatus.DLQ);
    }
}
