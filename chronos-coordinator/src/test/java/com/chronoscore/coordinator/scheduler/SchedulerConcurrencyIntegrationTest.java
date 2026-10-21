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
import com.chronoscore.coordinator.coordination.fencing.FencingTokenService;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.coordination.lock.DistributedLockService;
import com.chronoscore.coordinator.coordination.lock.LockHandle;
import com.chronoscore.coordinator.coordination.model.FencingToken;
import com.chronoscore.coordinator.distribution.WorkerSelector;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulerConcurrencyIntegrationTest {

    @Mock
    private LeaderElectionService leaderElectionService;
    @Mock
    private FencingTokenService fencingTokenService;
    @Mock
    private WorkerSelector workerSelector;
    @Mock
    private WorkerDispatchClient workerDispatchClient;
    @Mock
    private SchedulingPolicy schedulingPolicy;
    @Mock
    private TransactionalTaskService transactionalTaskService;

    @Test
    @DisplayName("Concurrent scheduling attempts on the same task guarantee exactly one successful dispatch")
    void testConcurrentSchedulingExclusivity() throws Exception {
        UUID taskId = UUID.randomUUID();
        Task initialTask = Task.builder()
                .taskId(taskId)
                .taskType("COMPUTE")
                .payload("{}")
                .priority(TaskPriority.HIGH)
                .status(TaskStatus.QUEUED)
                .tenantId("tenant-1")
                .createdAt(Instant.now())
                .timeout(Duration.ofSeconds(30))
                .maxAttempts(3)
                .currentAttempt(0)
                .version(0L)
                .build();

        Instant now = Instant.now();
        WorkerNode worker = new WorkerNode(
                "worker-alpha", "localhost", "127.0.0.1", 9091, 10, 0,
                java.util.Set.of("COMPUTE"), 1234L, "0.1.0-SNAPSHOT",
                UUID.randomUUID().toString(), WorkerState.ACTIVE,
                now, now, 0.0, 0.0
        );

        when(leaderElectionService.isLeader()).thenReturn(true);
        when(fencingTokenService.getCurrentFencingToken()).thenReturn(new FencingToken("coord-1", 1L, now));
        when(schedulingPolicy.selectCandidateTasks(any())).thenReturn(List.of(initialTask));
        when(workerSelector.selectWorker(any())).thenReturn(Optional.of(worker));

        ProtoTaskStateUpdate runningResponse = ProtoTaskStateUpdate.newBuilder()
                .setTaskId(taskId.toString())
                .setWorkerId("worker-alpha")
                .setNewStatus(ProtoTaskStatus.TASK_STATUS_RUNNING)
                .build();
        when(workerDispatchClient.dispatchTask(eq(worker), any(ProtoTaskAssignment.class), any()))
                .thenReturn(runningResponse);

        // Simulated thread-safe distributed lock implementation
        ConcurrentHashMap<String, LockHandle> activeLocks = new ConcurrentHashMap<>();
        DistributedLockService simulatedLockService = new DistributedLockService() {
            @Override
            public Optional<LockHandle> tryAcquire(String resource, Duration ttl) {
                LockHandle handle = new LockHandle(resource, UUID.randomUUID().toString(), Instant.now(), ttl);
                LockHandle existing = activeLocks.putIfAbsent(resource, handle);
                return existing == null ? Optional.of(handle) : Optional.empty();
            }

            @Override
            public boolean release(LockHandle handle) {
                return activeLocks.remove(handle.getResource(), handle);
            }

            @Override
            public boolean renew(LockHandle handle, Duration ttl) {
                return activeLocks.containsKey(handle.getResource());
            }

            @Override
            public boolean isOwner(LockHandle handle) {
                return activeLocks.containsKey(handle.getResource());
            }
        };

        // Simulated repository that mutates task state upon successful commit
        ConcurrentHashMap<UUID, Task> taskStore = new ConcurrentHashMap<>();
        taskStore.put(taskId, initialTask);

        TaskRepository simulatedRepository = new TaskRepository() {
            @Override public Task save(Task task) { taskStore.put(task.getTaskId(), task); return task; }
            @Override public Optional<Task> findById(UUID id) { return Optional.ofNullable(taskStore.get(id)); }
            @Override public Optional<Task> findByIdForUpdate(UUID id) { return findById(id); }
            @Override public Task update(Task task) { taskStore.put(task.getTaskId(), task); return task; }
            @Override public Optional<Task> findByIdempotencyKey(String t, String k) { return Optional.empty(); }
            @Override public boolean existsById(UUID id) { return taskStore.containsKey(id); }
            @Override public List<Task> findQueuedTasks(int limit) { return new ArrayList<>(taskStore.values()); }
            @Override public List<Task> findSchedulableTasks(Instant asOf, int limit) { return findQueuedTasks(limit); }
            @Override public List<Task> findStarvedTasks(Instant asOf, Duration wait, int limit) { return Collections.emptyList(); }
        };

        // Mutate repository state in transactionalTaskService mock
        when(transactionalTaskService.startExecutionAttempt(eq(taskId), anyString(), eq(1)))
                .thenAnswer(invocation -> {
                    taskStore.put(taskId, initialTask.toBuilder().status(TaskStatus.RUNNING).currentAttempt(1).build());
                    return null;
                });

        SchedulerProperties properties = new SchedulerProperties();
        properties.setEnabled(false);

        LeaderAwareTaskScheduler scheduler = new LeaderAwareTaskScheduler(
                leaderElectionService,
                fencingTokenService,
                simulatedLockService,
                simulatedRepository,
                transactionalTaskService,
                workerSelector,
                workerDispatchClient,
                schedulingPolicy,
                properties,
                new NoOpSchedulerMetricsRecorder()
        );

        // Concurrently invoke scheduleOnce from 10 threads simultaneously
        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger totalDispatched = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                SchedulingResult result = scheduler.scheduleOnce();
                totalDispatched.addAndGet(result.tasksDispatched());
                return null;
            }));
        }

        startLatch.countDown(); // Release all threads concurrently
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // INVARIANT: Across all 10 competing threads, EXACTLY ONE dispatch succeeded!
        assertThat(totalDispatched.get()).isEqualTo(1);
        // And task state in the store must be RUNNING
        assertThat(taskStore.get(taskId).getStatus()).isEqualTo(TaskStatus.RUNNING);
    }
}
