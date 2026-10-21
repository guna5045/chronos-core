package com.chronoscore.coordinator.recovery;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.cluster.registry.InMemoryWorkerRegistry;
import com.chronoscore.coordinator.coordination.lock.DistributedLockService;
import com.chronoscore.coordinator.coordination.lock.LockHandle;
import com.chronoscore.coordinator.persistence.exception.OptimisticLockingException;
import com.chronoscore.coordinator.persistence.model.OutboxEvent;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;
import com.chronoscore.coordinator.persistence.repository.ExecutionAttemptRepository;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.repository.TaskAuditRepository;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.recovery.config.LeaseProperties;
import com.chronoscore.coordinator.recovery.config.RetryProperties;
import com.chronoscore.coordinator.recovery.lease.ExecutionLeaseService;
import com.chronoscore.coordinator.recovery.lease.TaskExecutionLease;
import com.chronoscore.coordinator.recovery.lease.TaskLeaseRepository;
import com.chronoscore.coordinator.recovery.policy.DefaultRetryPolicy;
import com.chronoscore.coordinator.recovery.policy.JitterStrategy;
import com.chronoscore.coordinator.recovery.policy.RetryPolicy;
import com.chronoscore.coordinator.recovery.timeout.TaskTimeoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryConcurrencyIntegrationTest {

    private InMemoryTaskRepository taskRepository;
    private InMemoryAttemptRepository attemptRepository;
    private InMemoryLeaseRepository leaseRepository;
    private ExecutionLeaseService leaseService;
    private SimulatedDistributedLockService lockService;
    private TaskRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        taskRepository = new InMemoryTaskRepository();
        attemptRepository = new InMemoryAttemptRepository();
        leaseRepository = new InMemoryLeaseRepository();

        LeaseProperties leaseProperties = new LeaseProperties();
        leaseProperties.setDuration(Duration.ofSeconds(30));
        leaseService = new ExecutionLeaseService(leaseRepository, leaseProperties);

        RetryProperties retryProperties = new RetryProperties();
        retryProperties.setEnabled(true);
        retryProperties.setBaseDelay(Duration.ofMillis(100));
        retryProperties.setMaxDelay(Duration.ofSeconds(10));
        retryProperties.setMultiplier(2.0);
        retryProperties.setJitter(false);
        RetryPolicy retryPolicy = new DefaultRetryPolicy(retryProperties, JitterStrategy.none());

        lockService = new SimulatedDistributedLockService();

        TaskAuditRepository auditRepository = new TaskAuditRepository() {
            @Override public TaskAuditRecord append(TaskAuditRecord record) { return record; }
            @Override public List<TaskAuditRecord> findByTaskId(UUID taskId) { return Collections.emptyList(); }
        };

        OutboxRepository outboxRepository = new OutboxRepository() {
            @Override public OutboxEvent append(OutboxEvent event) { return event; }
            @Override public List<OutboxEvent> findPendingEvents(int limit) { return Collections.emptyList(); }
            @Override public boolean markPublished(UUID eventId, Instant publishedAt) { return true; }
            @Override public boolean incrementAttempt(UUID eventId) { return true; }
            @Override public Optional<OutboxEvent> findById(UUID eventId) { return Optional.empty(); }
        };

        InMemoryWorkerRegistry workerRegistry = new InMemoryWorkerRegistry();
        TaskTimeoutService timeoutService = mock(TaskTimeoutService.class);

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

    @Test
    @DisplayName("Concurrent recovery of the same worker failure by multiple threads performs exactly one recovery transition")
    void testConcurrentWorkerRecoveryIdempotency() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        String failedWorker = "worker-failed-1";

        Task task = Task.builder()
                .taskId(taskId)
                .tenantId("default-tenant")
                .taskType("COMPUTE")
                .payload("{}")
                .status(TaskStatus.RUNNING)
                .currentAttempt(1)
                .maxAttempts(3)
                .version(1L)
                .createdAt(Instant.now().minus(Duration.ofMinutes(1)))
                .build();
        taskRepository.save(task);

        ExecutionAttempt attempt = ExecutionAttempt.builder()
                .attemptId(attemptId)
                .taskId(taskId)
                .workerId(failedWorker)
                .attemptNumber(1)
                .status(ExecutionAttemptStatus.RUNNING)
                .startedAt(Instant.now().minus(Duration.ofSeconds(10)))
                .build();
        attemptRepository.save(attempt);

        TaskExecutionLease lease = TaskExecutionLease.builder()
                .taskId(taskId)
                .attemptId(attemptId)
                .workerId(failedWorker)
                .attemptNumber(1)
                .leaseAcquiredAt(Instant.now().minusSeconds(10))
                .leaseExpiresAt(Instant.now().plusSeconds(20))
                .build();
        leaseRepository.save(lease);

        int concurrency = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        AtomicInteger successfulRecoveries = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int count = recoveryService.recoverWorkerFailure(failedWorker);
                    successfulRecoveries.addAndGet(count);
                } catch (Exception e) {
                    // Ignored
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completedInTime = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completedInTime).isTrue();

        // Exactly one thread should have performed the recovery transition
        assertThat(successfulRecoveries.get()).isEqualTo(1);

        // Final task state must be QUEUED for next attempt
        Task finalTask = taskRepository.findById(taskId).orElseThrow();
        assertThat(finalTask.getStatus()).isEqualTo(TaskStatus.QUEUED);

        // Attempt 1 must be marked FAILED
        ExecutionAttempt finalAttempt = attemptRepository.findByTaskIdAndAttemptNumber(taskId, 1).orElseThrow();
        assertThat(finalAttempt.getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
        assertThat(finalAttempt.getErrorCode()).isEqualTo("WORKER_UNAVAILABLE");

        // Lease must be cleaned up
        assertThat(leaseRepository.findByTaskId(taskId)).isEmpty();
    }

    private static class SimulatedDistributedLockService implements DistributedLockService {
        private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

        @Override
        public Optional<LockHandle> tryAcquire(String resource, Duration ttl) {
            ReentrantLock lock = locks.computeIfAbsent(resource, k -> new ReentrantLock());
            if (lock.tryLock()) {
                return Optional.of(new LockHandle(resource, "owner", Instant.now(), ttl));
            }
            return Optional.empty();
        }

        @Override
        public boolean release(LockHandle handle) {
            ReentrantLock lock = locks.get(handle.getResource());
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
                return true;
            }
            return false;
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) { return true; }

        @Override
        public boolean isOwner(LockHandle handle) { return true; }
    }

    private static class InMemoryTaskRepository implements TaskRepository {
        private final Map<UUID, Task> store = new ConcurrentHashMap<>();

        @Override public Task save(Task task) { store.put(task.getTaskId(), task); return task; }
        @Override public Optional<Task> findById(UUID id) { return Optional.ofNullable(store.get(id)); }
        @Override public Optional<Task> findByIdForUpdate(UUID id) { return findById(id); }
        @Override public Task update(Task task) {
            Task current = store.get(task.getTaskId());
            if (current != null && current.getVersion() != task.getVersion()) {
                throw new OptimisticLockingException(task.getTaskId(), task.getVersion());
            }
            Task updated = task.toBuilder().version(task.getVersion() + 1).build();
            store.put(task.getTaskId(), updated);
            return updated;
        }
        @Override public Optional<Task> findByIdempotencyKey(String t, String k) { return Optional.empty(); }
        @Override public boolean existsById(UUID id) { return store.containsKey(id); }
        @Override public List<Task> findQueuedTasks(int limit) { return Collections.emptyList(); }
        @Override public List<Task> findSchedulableTasks(Instant asOf, int limit) { return Collections.emptyList(); }
        @Override public List<Task> findStarvedTasks(Instant asOf, Duration wait, int limit) { return Collections.emptyList(); }
        @Override public List<Task> findByStatus(TaskStatus status, int limit) {
            return store.values().stream().filter(t -> t.getStatus() == status).limit(limit).toList();
        }
    }

    private static class InMemoryAttemptRepository implements ExecutionAttemptRepository {
        private final Map<String, ExecutionAttempt> store = new ConcurrentHashMap<>();

        private String key(UUID taskId, int num) { return taskId + ":" + num; }

        @Override public ExecutionAttempt save(ExecutionAttempt attempt) {
            store.put(key(attempt.getTaskId(), attempt.getAttemptNumber()), attempt);
            return attempt;
        }
        @Override public Optional<ExecutionAttempt> findById(UUID id) {
            return store.values().stream().filter(a -> a.getAttemptId().equals(id)).findFirst();
        }
        @Override public List<ExecutionAttempt> findByTaskId(UUID taskId) {
            return store.values().stream().filter(a -> a.getTaskId().equals(taskId)).toList();
        }
        @Override public Optional<ExecutionAttempt> findByTaskIdAndAttemptNumber(UUID taskId, int num) {
            return Optional.ofNullable(store.get(key(taskId, num)));
        }
        @Override public Optional<ExecutionAttempt> findLatestAttempt(UUID taskId) {
            return store.values().stream()
                    .filter(a -> a.getTaskId().equals(taskId))
                    .max(Comparator.comparingInt(ExecutionAttempt::getAttemptNumber));
        }
        @Override public ExecutionAttempt update(ExecutionAttempt attempt) {
            store.put(key(attempt.getTaskId(), attempt.getAttemptNumber()), attempt);
            return attempt;
        }
    }

    private static class InMemoryLeaseRepository implements TaskLeaseRepository {
        private final Map<UUID, TaskExecutionLease> store = new ConcurrentHashMap<>();

        @Override public TaskExecutionLease save(TaskExecutionLease lease) { store.put(lease.getTaskId(), lease); return lease; }
        @Override public Optional<TaskExecutionLease> findByTaskId(UUID taskId) { return Optional.ofNullable(store.get(taskId)); }
        @Override public List<TaskExecutionLease> findByWorkerId(String workerId) {
            return store.values().stream().filter(l -> l.getWorkerId().equals(workerId)).toList();
        }
        @Override public List<TaskExecutionLease> findExpiredLeases(Instant asOf, int limit) {
            return store.values().stream().filter(l -> l.getLeaseExpiresAt().isBefore(asOf)).limit(limit).toList();
        }
        @Override public boolean renewLease(UUID taskId, Instant newExpiresAt) { return true; }
        @Override public boolean releaseLease(UUID taskId) { return store.remove(taskId) != null; }
    }

    @SuppressWarnings("unchecked")
    private static <T> T mock(Class<T> clazz) {
        return org.mockito.Mockito.mock(clazz);
    }
}
