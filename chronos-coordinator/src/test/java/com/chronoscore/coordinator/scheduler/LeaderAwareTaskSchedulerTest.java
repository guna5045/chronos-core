package com.chronoscore.coordinator.scheduler;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.proto.ProtoTaskAssignment;
import com.chronoscore.common.proto.ProtoTaskStateUpdate;
import com.chronoscore.common.proto.ProtoTaskStatus;
import com.chronoscore.coordinator.cluster.grpc.WorkerDispatchClient;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import com.chronoscore.coordinator.coordination.exception.StaleLeaderException;
import com.chronoscore.coordinator.coordination.fencing.FencingTokenService;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.coordination.lock.DistributedLockService;
import com.chronoscore.coordinator.coordination.lock.LockHandle;
import com.chronoscore.coordinator.coordination.model.FencingToken;
import com.chronoscore.coordinator.coordination.model.LeaderIdentity;
import com.chronoscore.coordinator.distribution.WorkerSelector;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaderAwareTaskSchedulerTest {

    @Mock
    private LeaderElectionService leaderElectionService;
    @Mock
    private FencingTokenService fencingTokenService;
    @Mock
    private DistributedLockService distributedLockService;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TransactionalTaskService transactionalTaskService;
    @Mock
    private WorkerSelector workerSelector;
    @Mock
    private WorkerDispatchClient workerDispatchClient;
    @Mock
    private SchedulingPolicy schedulingPolicy;
    @Mock
    private SchedulerMetricsRecorder metricsRecorder;

    private SchedulerProperties schedulerProperties;
    private LeaderAwareTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        schedulerProperties = new SchedulerProperties();
        schedulerProperties.setEnabled(false); // Background loop disabled; manual trigger for unit testing
        schedulerProperties.setBatchSize(10);
        schedulerProperties.setDispatchTimeout(Duration.ofSeconds(2));
        schedulerProperties.setTaskLockTtl(Duration.ofSeconds(10));

        scheduler = new LeaderAwareTaskScheduler(
                leaderElectionService,
                fencingTokenService,
                distributedLockService,
                taskRepository,
                transactionalTaskService,
                workerSelector,
                workerDispatchClient,
                schedulingPolicy,
                schedulerProperties,
                metricsRecorder
        );
    }

    private Task createSampleTask(UUID taskId, TaskStatus status, Instant deadline) {
        return Task.builder()
                .taskId(taskId != null ? taskId : UUID.randomUUID())
                .taskType("COMPUTE")
                .payload("{\"input\":42}")
                .priority(TaskPriority.HIGH)
                .status(status != null ? status : TaskStatus.QUEUED)
                .tenantId("tenant-1")
                .createdAt(Instant.now().minusSeconds(10))
                .deadline(deadline)
                .timeout(Duration.ofSeconds(30))
                .maxAttempts(3)
                .currentAttempt(0)
                .version(0L)
                .build();
    }

    private WorkerNode createWorker(String workerId) {
        Instant now = Instant.now();
        return new WorkerNode(
                workerId,
                "localhost",
                "127.0.0.1",
                9091,
                8,
                2,
                java.util.Set.of("COMPUTE"),
                1000L,
                "0.1.0-SNAPSHOT",
                UUID.randomUUID().toString(),
                WorkerState.ACTIVE,
                now,
                now,
                0.0,
                0.0
        );
    }

    @Test
    @DisplayName("Standby coordinator (FOLLOWER) does not execute scheduling operations")
    void testFollowerDoesNotSchedule() {
        when(leaderElectionService.isLeader()).thenReturn(false);

        SchedulingResult result = scheduler.scheduleOnce();

        assertThat(result.active()).isFalse();
        assertThat(result.tasksDispatched()).isEqualTo(0);
        verify(schedulingPolicy, never()).selectCandidateTasks(any());
    }

    @Test
    @DisplayName("Scheduler aborts immediately if stale leader epoch is detected")
    void testStaleLeaderFailsFast() {
        when(leaderElectionService.isLeader()).thenReturn(true);
        when(fencingTokenService.getCurrentFencingToken())
                .thenThrow(new StaleLeaderException("Leadership superseded by epoch 2"));

        SchedulingResult result = scheduler.scheduleOnce();

        assertThat(result.active()).isFalse();
        assertThat(result.message()).contains("Stale leader");
        verify(schedulingPolicy, never()).selectCandidateTasks(any());
    }

    @Test
    @DisplayName("Leader successfully selects, locks, and dispatches task to worker over gRPC")
    void testSuccessfulTaskDispatch() {
        UUID taskId = UUID.randomUUID();
        Task task = createSampleTask(taskId, TaskStatus.QUEUED, null);
        WorkerNode worker = createWorker("worker-alpha");
        LockHandle lockHandle = new LockHandle("chronos:task:lock:" + taskId, "owner-token-123", Instant.now(), Duration.ofSeconds(10));

        when(leaderElectionService.isLeader()).thenReturn(true);
        when(fencingTokenService.getCurrentFencingToken()).thenReturn(new FencingToken("coord-1", 1L, Instant.now()));
        when(schedulingPolicy.selectCandidateTasks(any())).thenReturn(List.of(task));
        when(distributedLockService.tryAcquire(eq("chronos:task:lock:" + taskId), any(Duration.class)))
                .thenReturn(Optional.of(lockHandle));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(workerSelector.selectWorker(task)).thenReturn(Optional.of(worker));

        ProtoTaskStateUpdate runningResponse = ProtoTaskStateUpdate.newBuilder()
                .setTaskId(taskId.toString())
                .setWorkerId("worker-alpha")
                .setNewStatus(ProtoTaskStatus.TASK_STATUS_RUNNING)
                .build();
        when(workerDispatchClient.dispatchTask(eq(worker), any(ProtoTaskAssignment.class), any(Duration.class)))
                .thenReturn(runningResponse);

        SchedulingResult result = scheduler.scheduleOnce();

        assertThat(result.active()).isTrue();
        assertThat(result.tasksSelected()).isEqualTo(1);
        assertThat(result.tasksDispatched()).isEqualTo(1);

        // Verify lock acquisition and safe release
        verify(distributedLockService).release(lockHandle);
        // Verify database transition to RUNNING
        verify(transactionalTaskService).startExecutionAttempt(taskId, "worker-alpha", 1);
    }

    @Test
    @DisplayName("Task whose deadline has expired is transitioned to CANCELLED without worker dispatch")
    void testExpiredDeadlineHandling() {
        UUID taskId = UUID.randomUUID();
        // Deadline 5 seconds in the past
        Task expiredTask = createSampleTask(taskId, TaskStatus.QUEUED, Instant.now().minusSeconds(5));
        LockHandle lockHandle = new LockHandle("chronos:task:lock:" + taskId, "owner-token", Instant.now(), Duration.ofSeconds(10));

        when(leaderElectionService.isLeader()).thenReturn(true);
        when(fencingTokenService.getCurrentFencingToken()).thenReturn(new FencingToken("coord-1", 1L, Instant.now()));
        when(schedulingPolicy.selectCandidateTasks(any())).thenReturn(List.of(expiredTask));
        when(distributedLockService.tryAcquire(eq("chronos:task:lock:" + taskId), any(Duration.class)))
                .thenReturn(Optional.of(lockHandle));

        SchedulingResult result = scheduler.scheduleOnce();

        assertThat(result.deadlinesExpired()).isEqualTo(1);
        assertThat(result.tasksDispatched()).isEqualTo(0);

        // Verify task transitioned to CANCELLED in PostgreSQL
        verify(transactionalTaskService).transitionTask(eq(taskId), eq(TaskStatus.CANCELLED), anyString(), any());
        // Verify lock safely released
        verify(distributedLockService).release(lockHandle);
        // Verify worker was NEVER contacted
        verify(workerDispatchClient, never()).dispatchTask(any(), any(), any());
    }

    @Test
    @DisplayName("Task is safely skipped if distributed lock is already held by another coordinator")
    void testLockedTaskIsSkipped() {
        UUID taskId = UUID.randomUUID();
        Task task = createSampleTask(taskId, TaskStatus.QUEUED, null);

        when(leaderElectionService.isLeader()).thenReturn(true);
        when(fencingTokenService.getCurrentFencingToken()).thenReturn(new FencingToken("coord-1", 1L, Instant.now()));
        when(schedulingPolicy.selectCandidateTasks(any())).thenReturn(List.of(task));
        // Lock acquisition fails (held by another node)
        when(distributedLockService.tryAcquire(eq("chronos:task:lock:" + taskId), any(Duration.class)))
                .thenReturn(Optional.empty());

        SchedulingResult result = scheduler.scheduleOnce();

        assertThat(result.tasksSkipped()).isEqualTo(1);
        assertThat(result.tasksDispatched()).isEqualTo(0);
        verify(workerSelector, never()).selectWorker(any());
    }

    @Test
    @DisplayName("Worker dispatch rejection leaves task in QUEUED status without corrupting DB")
    void testWorkerRejectionPreservesQueuedState() {
        UUID taskId = UUID.randomUUID();
        Task task = createSampleTask(taskId, TaskStatus.QUEUED, null);
        WorkerNode worker = createWorker("worker-beta");
        LockHandle lockHandle = new LockHandle("chronos:task:lock:" + taskId, "owner-token", Instant.now(), Duration.ofSeconds(10));

        when(leaderElectionService.isLeader()).thenReturn(true);
        when(fencingTokenService.getCurrentFencingToken()).thenReturn(new FencingToken("coord-1", 1L, Instant.now()));
        when(schedulingPolicy.selectCandidateTasks(any())).thenReturn(List.of(task));
        when(distributedLockService.tryAcquire(eq("chronos:task:lock:" + taskId), any(Duration.class)))
                .thenReturn(Optional.of(lockHandle));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(workerSelector.selectWorker(task)).thenReturn(Optional.of(worker));

        // Worker throws RESOURCE_EXHAUSTED
        when(workerDispatchClient.dispatchTask(eq(worker), any(), any()))
                .thenThrow(new StatusRuntimeException(Status.RESOURCE_EXHAUSTED.withDescription("Capacity saturated")));

        SchedulingResult result = scheduler.scheduleOnce();

        assertThat(result.tasksRejected()).isEqualTo(1);
        assertThat(result.tasksDispatched()).isEqualTo(0);

        // Crucial invariant: startExecutionAttempt must NOT have been called
        verify(transactionalTaskService, never()).startExecutionAttempt(any(), any(), anyInt());
        // Lock must still be safely released
        verify(distributedLockService).release(lockHandle);
    }

    @Test
    @DisplayName("Leadership loss callback pauses scheduler")
    void testLeadershipLossCallback() {
        LeaderIdentity identity = LeaderIdentity.lost("coord-1", 1L);
        scheduler.onLeadershipLost(identity);

        assertThat(scheduler.isRunning()).isFalse();
    }
}
