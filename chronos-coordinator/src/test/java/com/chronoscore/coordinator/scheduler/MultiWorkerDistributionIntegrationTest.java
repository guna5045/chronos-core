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
import com.chronoscore.coordinator.cluster.registry.InMemoryWorkerRegistry;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import com.chronoscore.coordinator.coordination.fencing.FencingTokenService;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.coordination.lock.DistributedLockService;
import com.chronoscore.coordinator.coordination.lock.LockHandle;
import com.chronoscore.coordinator.coordination.model.FencingToken;
import com.chronoscore.coordinator.distribution.ConsistentHashRing;
import com.chronoscore.coordinator.distribution.ConsistentHashWorkerSelector;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiWorkerDistributionIntegrationTest {

    @Mock
    private LeaderElectionService leaderElectionService;
    @Mock
    private FencingTokenService fencingTokenService;
    @Mock
    private WorkerDispatchClient workerDispatchClient;
    @Mock
    private TransactionalTaskService transactionalTaskService;

    private WorkerRegistry workerRegistry;
    private ConsistentHashRing consistentHashRing;
    private ConsistentHashWorkerSelector workerSelector;
    private ConcurrentHashMap<UUID, Task> taskStore;
    private TaskRepository taskRepository;

    @BeforeEach
    void setUp() {
        workerRegistry = new InMemoryWorkerRegistry();
        consistentHashRing = new ConsistentHashRing(128);
        workerSelector = new ConsistentHashWorkerSelector(consistentHashRing, workerRegistry);

        taskStore = new ConcurrentHashMap<>();
        taskRepository = new TaskRepository() {
            @Override public Task save(Task task) { taskStore.put(task.getTaskId(), task); return task; }
            @Override public Optional<Task> findById(UUID id) { return Optional.ofNullable(taskStore.get(id)); }
            @Override public Optional<Task> findByIdForUpdate(UUID id) { return findById(id); }
            @Override public Task update(Task task) { taskStore.put(task.getTaskId(), task); return task; }
            @Override public Optional<Task> findByIdempotencyKey(String t, String k) { return Optional.empty(); }
            @Override public boolean existsById(UUID id) { return taskStore.containsKey(id); }
            @Override public List<Task> findQueuedTasks(int limit) {
                return taskStore.values().stream()
                        .filter(t -> t.getStatus() == TaskStatus.QUEUED)
                        .limit(limit)
                        .toList();
            }
            @Override public List<Task> findSchedulableTasks(Instant asOf, int limit) { return findQueuedTasks(limit); }
            @Override public List<Task> findStarvedTasks(Instant asOf, Duration wait, int limit) { return Collections.emptyList(); }
            @Override public List<Task> findByStatus(TaskStatus status, int limit) {
                return taskStore.values().stream()
                        .filter(t -> t.getStatus() == status)
                        .limit(limit)
                        .toList();
            }
        };
    }

    private DistributedLockService createNoOpLockService() {
        return new DistributedLockService() {
            @Override public Optional<LockHandle> tryAcquire(String resource, Duration ttl) {
                return Optional.of(new LockHandle(resource, "owner", Instant.now(), ttl));
            }
            @Override public boolean release(LockHandle handle) { return true; }
            @Override public boolean renew(LockHandle handle, Duration ttl) { return true; }
            @Override public boolean isOwner(LockHandle handle) { return true; }
        };
    }

    private WorkerNode createWorker(String workerId, int port, int maxConcurrency) {
        Instant now = Instant.now();
        return new WorkerNode(
                workerId,
                "localhost",
                "127.0.0.1",
                port,
                maxConcurrency,
                0,
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
    @DisplayName("Tasks are distributed across multiple workers, respecting capacity and excluding removed workers")
    void testMultiWorkerTaskDistribution() {
        // Register Worker A, Worker B, Worker C with capacity 10 each
        WorkerNode workerA = workerRegistry.registerWorker(createWorker("worker-A", 9091, 10));
        WorkerNode workerB = workerRegistry.registerWorker(createWorker("worker-B", 9092, 10));
        WorkerNode workerC = workerRegistry.registerWorker(createWorker("worker-C", 9093, 10));

        // Record initial heartbeats to make them ACTIVE
        workerRegistry.recordHeartbeat("worker-A", workerA.sessionId(), 0, 0.0, 0.0);
        workerRegistry.recordHeartbeat("worker-B", workerB.sessionId(), 0, 0.0, 0.0);
        workerRegistry.recordHeartbeat("worker-C", workerC.sessionId(), 0, 0.0, 0.0);

        // Create 30 queued tasks
        int taskCount = 30;
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            UUID id = UUID.randomUUID();
            Task task = Task.builder()
                    .taskId(id)
                    .taskType("COMPUTE")
                    .payload("{\"index\":" + i + "}")
                    .priority(TaskPriority.NORMAL)
                    .status(TaskStatus.QUEUED)
                    .tenantId("tenant-1")
                    .createdAt(Instant.now().minusSeconds(i))
                    .timeout(Duration.ofSeconds(30))
                    .maxAttempts(3)
                    .currentAttempt(0)
                    .version(0L)
                    .build();
            taskStore.put(id, task);
            tasks.add(task);
        }

        when(leaderElectionService.isLeader()).thenReturn(true);
        when(fencingTokenService.getCurrentFencingToken()).thenReturn(new FencingToken("coord-leader", 1L, Instant.now()));

        // Track dispatches per worker
        Map<String, AtomicInteger> workerDispatches = new ConcurrentHashMap<>();
        workerDispatches.put("worker-A", new AtomicInteger(0));
        workerDispatches.put("worker-B", new AtomicInteger(0));
        workerDispatches.put("worker-C", new AtomicInteger(0));

        when(workerDispatchClient.dispatchTask(any(WorkerNode.class), any(ProtoTaskAssignment.class), any()))
                .thenAnswer(invocation -> {
                    WorkerNode targetWorker = invocation.getArgument(0);
                    ProtoTaskAssignment assignment = invocation.getArgument(1);

                    workerDispatches.get(targetWorker.workerId()).incrementAndGet();
                    return ProtoTaskStateUpdate.newBuilder()
                            .setTaskId(assignment.getTaskId())
                            .setWorkerId(targetWorker.workerId())
                            .setNewStatus(ProtoTaskStatus.TASK_STATUS_RUNNING)
                            .build();
                });

        org.mockito.Mockito.lenient().when(transactionalTaskService.getNextAttemptNumber(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> (int) inv.getArgument(1) + 1);

        when(transactionalTaskService.startExecutionAttempt(any(UUID.class), anyString(), eq(1)))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    Task t = taskStore.get(id);
                    taskStore.put(id, t.toBuilder().status(TaskStatus.RUNNING).currentAttempt(1).build());
                    return null;
                });

        SchedulerProperties properties = new SchedulerProperties();
        properties.setEnabled(false);
        properties.setBatchSize(50);

        SchedulingPolicy policy = new SchedulingPolicy(taskRepository, properties);

        LeaderAwareTaskScheduler scheduler = new LeaderAwareTaskScheduler(
                leaderElectionService,
                fencingTokenService,
                createNoOpLockService(),
                taskRepository,
                transactionalTaskService,
                workerSelector,
                workerDispatchClient,
                policy,
                properties,
                new NoOpSchedulerMetricsRecorder()
        );

        // Run scheduling iteration
        SchedulingResult result = scheduler.scheduleOnce();

        assertThat(result.active()).isTrue();
        assertThat(result.tasksSelected()).isEqualTo(taskCount);
        assertThat(result.tasksDispatched()).isEqualTo(taskCount);

        // Verify that all 3 active workers received a share of tasks
        assertThat(workerDispatches.get("worker-A").get()).isGreaterThan(0);
        assertThat(workerDispatches.get("worker-B").get()).isGreaterThan(0);
        assertThat(workerDispatches.get("worker-C").get()).isGreaterThan(0);

        int total = workerDispatches.get("worker-A").get()
                + workerDispatches.get("worker-B").get()
                + workerDispatches.get("worker-C").get();
        assertThat(total).isEqualTo(taskCount);

        // Now remove Worker C
        workerRegistry.removeWorker("worker-C", workerC.sessionId());

        // Submit 10 more tasks
        for (int i = 0; i < 10; i++) {
            UUID id = UUID.randomUUID();
            Task task = Task.builder()
                    .taskId(id)
                    .taskType("COMPUTE")
                    .payload("{\"new_index\":" + i + "}")
                    .priority(TaskPriority.NORMAL)
                    .status(TaskStatus.QUEUED)
                    .tenantId("tenant-1")
                    .createdAt(Instant.now())
                    .timeout(Duration.ofSeconds(30))
                    .maxAttempts(3)
                    .currentAttempt(0)
                    .version(0L)
                    .build();
            taskStore.put(id, task);
        }

        int workerCDispatchesBefore = workerDispatches.get("worker-C").get();
        SchedulingResult secondBatch = scheduler.scheduleOnce();

        assertThat(secondBatch.tasksDispatched()).isEqualTo(10);
        // Crucial invariant: Removed Worker C received ZERO new tasks!
        assertThat(workerDispatches.get("worker-C").get()).isEqualTo(workerCDispatchesBefore);
    }
}
