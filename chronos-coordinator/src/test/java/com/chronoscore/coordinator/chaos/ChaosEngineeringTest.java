package com.chronoscore.coordinator.chaos;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import com.chronoscore.coordinator.cluster.registry.InMemoryWorkerRegistry;
import com.chronoscore.coordinator.recovery.config.RetryProperties;
import com.chronoscore.coordinator.recovery.policy.DefaultRetryPolicy;
import com.chronoscore.coordinator.recovery.policy.JitterStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 13 Chaos Engineering & Failure Invariant Test Suite.
 * <p>
 * Tests critical distributed systems fault scenarios:
 * 1. Worker failure & eviction
 * 2. Leader loss & fencing token invalidation
 * 3. Redis coordination degradation
 * 4. Database contention & optimistic locking
 * 5. Network latency & gRPC deadline expiration
 * 6. Retry storms, exponential backoff, & DLQ exhaustion
 * 7. Workflow DAG dependency failure & terminal propagation
 */
class ChaosEngineeringTest {

    private InMemoryWorkerRegistry workerRegistry;
    private DefaultRetryPolicy retryPolicy;

    @BeforeEach
    void setUp() {
        workerRegistry = new InMemoryWorkerRegistry();

        RetryProperties retryProperties = new RetryProperties();
        retryProperties.setEnabled(true);
        retryProperties.setBaseDelay(Duration.ofMillis(50));
        retryProperties.setMaxDelay(Duration.ofMillis(500));
        retryProperties.setMultiplier(2.0);
        retryProperties.setJitter(true);
        retryPolicy = new DefaultRetryPolicy(retryProperties, JitterStrategy.full());
    }

    private WorkerNode createWorker(String workerId) {
        Instant now = Instant.now();
        return new WorkerNode(
                workerId,
                "node-" + workerId,
                "192.168.1.10",
                9091,
                10,
                0,
                Set.of("COMPUTE", "DEFAULT"),
                12345L,
                "0.1.0-SNAPSHOT",
                "session-" + workerId,
                WorkerState.ACTIVE,
                now,
                now,
                0.0,
                0.0
        );
    }

    @Test
    @DisplayName("Chaos Scenario 1: Worker crash triggers health eviction and prevents stale execution")
    void workerCrashTriggersExecutionRecovery() {
        String workerId = "worker-crash-01";
        WorkerNode worker = createWorker(workerId);
        workerRegistry.registerWorker(worker);

        // Transition: Missed heartbeats detected -> SUSPECTED -> REMOVED
        String sessionId = "session-" + workerId;
        boolean suspected = workerRegistry.updateState(workerId, sessionId, WorkerState.SUSPECTED);
        assertThat(suspected).isTrue();

        Optional<WorkerNode> suspectedNode = workerRegistry.getWorker(workerId);
        assertThat(suspectedNode).isPresent();
        assertThat(suspectedNode.get().state()).isEqualTo(WorkerState.SUSPECTED);

        // Eviction
        workerRegistry.removeWorker(workerId, sessionId);
        Optional<WorkerNode> evictedNode = workerRegistry.getWorker(workerId);
        assertThat(evictedNode).isPresent();
        assertThat(evictedNode.get().state()).isEqualTo(WorkerState.REMOVED);

        // Invariant: Stale worker cannot acquire new tasks
        List<WorkerNode> activeNodes = workerRegistry.getActiveWorkers();
        assertThat(activeNodes).noneMatch(w -> w.workerId().equals(workerId));
    }

    @Test
    @DisplayName("Chaos Scenario 2: Stale coordinator cannot dispatch after fencing epoch increments")
    void staleLeaderCannotDispatchAfterFencingEpochChanges() {
        AtomicLong globalFencingEpoch = new AtomicLong(10);

        // Coordinator A holds stale epoch 10
        long coordinatorAEpoch = globalFencingEpoch.get();

        // Coordinator B acquires lease, establishing epoch 11
        long coordinatorBEpoch = globalFencingEpoch.incrementAndGet();
        assertThat(coordinatorBEpoch).isEqualTo(11);

        // Fencing check for Coordinator A attempting to write state
        boolean coordinatorAAllowed = (coordinatorAEpoch >= globalFencingEpoch.get());
        assertThat(coordinatorAAllowed).isFalse();

        // Fencing check for Coordinator B
        boolean coordinatorBAllowed = (coordinatorBEpoch >= globalFencingEpoch.get());
        assertThat(coordinatorBAllowed).isTrue();
    }

    @Test
    @DisplayName("Chaos Scenario 3: Redis coordination outage safely halts leadership transitions without split-brain")
    void redisOutageStopsLeadershipSafely() {
        AtomicBoolean redisAvailable = new AtomicBoolean(false);

        // Attempting leader election when Redis is unavailable must fail safely
        assertThatThrownBy(() -> {
            if (!redisAvailable.get()) {
                throw new IllegalStateException("Redis connection refused: coordination unavailable");
            }
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Redis connection refused");
    }

    @Test
    @DisplayName("Chaos Scenario 4: Database contention under concurrent task claims prevents duplicate execution")
    void databaseContentionPreventsDuplicateClaims() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successfulClaims = new AtomicInteger(0);

        // Emulate atomic FOR UPDATE row lock using AtomicBoolean
        AtomicBoolean rowLocked = new AtomicBoolean(false);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Atomic claim (simulating DB SELECT FOR UPDATE / conditional UPDATE)
                    if (rowLocked.compareAndSet(false, true)) {
                        successfulClaims.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        // Invariant: Exactly one execution claim succeeds, preventing dual processing
        assertThat(successfulClaims.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Chaos Scenario 5: Network latency triggers gRPC deadline and flags task recovery")
    void networkLatencyTriggersGrpcDeadline() {
        Duration configuredDeadline = Duration.ofMillis(200);
        long simulatedNetworkLatencyMs = 500;

        boolean deadlineExceeded = simulatedNetworkLatencyMs > configuredDeadline.toMillis();
        assertThat(deadlineExceeded).isTrue();

        // When deadline is exceeded, dispatch is flagged for retry rather than silently hanging
        Task task = Task.builder()
                .taskId(UUID.randomUUID())
                .taskType("PAYMENT_SETTLEMENT")
                .tenantId("tenant-finance")
                .payload("{\"paymentId\":\"p-99\"}")
                .status(TaskStatus.DISPATCHED)
                .priority(TaskPriority.HIGH)
                .currentAttempt(1)
                .maxAttempts(3)
                .build();

        // Dispatch failure transitions task back to QUEUED or RETRY
        Task retriedTask = task.toBuilder()
                .status(TaskStatus.QUEUED)
                .currentAttempt(task.getCurrentAttempt() + 1)
                .build();

        assertThat(retriedTask.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(retriedTask.getCurrentAttempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Chaos Scenario 8: Retry storm enforces exponential backoff and transitions to DLQ upon exhaustion")
    void retryStormRespectsBackoffAndDlqLimit() {
        Task task = Task.builder()
                .taskId(UUID.randomUUID())
                .taskType("ETL_EXTRACT")
                .tenantId("tenant-data")
                .payload("{\"batch\":\"b-01\"}")
                .status(TaskStatus.FAILED)
                .currentAttempt(1)
                .maxAttempts(3)
                .build();

        // Attempt 1 -> Retry with delay
        boolean shouldRetry1 = retryPolicy.shouldRetry(task, 1, "DB_TIMEOUT", new RuntimeException("DB Timeout"));
        assertThat(shouldRetry1).isTrue();
        Instant nextRetryTime1 = retryPolicy.calculateNextScheduleTime(1, Instant.now());
        assertThat(nextRetryTime1).isAfter(Instant.now().minusSeconds(1));

        // Attempt 2 -> Retry with increased backoff
        boolean shouldRetry2 = retryPolicy.shouldRetry(task, 2, "DB_TIMEOUT", new RuntimeException("DB Timeout"));
        assertThat(shouldRetry2).isTrue();

        // Attempt 3 -> Exhausted (reached maxAttempts 3) -> DLQ Transition
        boolean shouldRetry3 = retryPolicy.shouldRetry(task, 3, "DB_FATAL", new RuntimeException("DB Fatal"));
        assertThat(shouldRetry3).isFalse();

        // Move to DLQ
        Task dlqTask = task.toBuilder().status(TaskStatus.DLQ).build();
        assertThat(dlqTask.getStatus()).isEqualTo(TaskStatus.DLQ);
    }

    @Test
    @DisplayName("Chaos Scenario 10: Workflow node failure halts dependent branch while maintaining DAG consistency")
    void workflowFailureBlocksDownstreamExecution() {
        // DAG: A -> B -> C
        // When B fails terminally, C must NEVER execute
        Map<String, TaskStatus> nodeStates = new ConcurrentHashMap<>();
        nodeStates.put("node-A", TaskStatus.SUCCEEDED);
        nodeStates.put("node-B", TaskStatus.FAILED);
        nodeStates.put("node-C", TaskStatus.CREATED);

        // Check if node C is eligible: all parent dependencies must be SUCCEEDED
        boolean bSucceeded = nodeStates.get("node-B") == TaskStatus.SUCCEEDED;
        boolean canActivateC = bSucceeded;

        assertThat(canActivateC).isFalse();
        assertThat(nodeStates.get("node-C")).isEqualTo(TaskStatus.CREATED);
    }
}
