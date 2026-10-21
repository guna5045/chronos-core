package com.chronoscore.coordinator.cluster.registry;

import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerRegistryTest {

    private InMemoryWorkerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryWorkerRegistry();
    }

    private WorkerNode createWorker(String workerId, String sessionId, int maxConcurrency) {
        Instant now = Instant.now();
        return new WorkerNode(
                workerId,
                "node-" + workerId,
                "192.168.1.10",
                9091,
                maxConcurrency,
                0,
                Set.of("COMPUTE", "DEFAULT"),
                12345L,
                "0.1.0-SNAPSHOT",
                sessionId,
                WorkerState.ACTIVE,
                now,
                now,
                0.0,
                0.0
        );
    }

    @Test
    @DisplayName("registerWorker: stores worker and exposes lookup by ID and state")
    void testRegisterAndLookup() {
        WorkerNode worker = createWorker("worker-1", "session-1", 8);

        WorkerNode registered = registry.registerWorker(worker);
        assertThat(registered).isNotNull();
        assertThat(registered.workerId()).isEqualTo("worker-1");

        Optional<WorkerNode> retrieved = registry.getWorker("worker-1");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().sessionId()).isEqualTo("session-1");
        assertThat(retrieved.get().maxConcurrency()).isEqualTo(8);
        assertThat(retrieved.get().state()).isEqualTo(WorkerState.ACTIVE);

        assertThat(registry.getActiveWorkers()).hasSize(1);
        assertThat(registry.getActiveWorkerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("re-registration: replaces previous session with fresh active session")
    void testReRegistration() {
        WorkerNode initial = createWorker("worker-2", "session-old", 8);
        registry.registerWorker(initial);

        WorkerNode updated = createWorker("worker-2", "session-new", 16);
        registry.registerWorker(updated);

        Optional<WorkerNode> retrieved = registry.getWorker("worker-2");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().sessionId()).isEqualTo("session-new");
        assertThat(retrieved.get().maxConcurrency()).isEqualTo(16);
    }

    @Test
    @DisplayName("recordHeartbeat: updates last seen timestamp and active tasks when session matches")
    void testHeartbeatSuccess() {
        WorkerNode worker = createWorker("worker-3", "session-3", 10);
        registry.registerWorker(worker);

        boolean recorded = registry.recordHeartbeat("worker-3", "session-3", 4, 25.5, 45.0);
        assertThat(recorded).isTrue();

        WorkerNode updated = registry.getWorker("worker-3").orElseThrow();
        assertThat(updated.activeTasks()).isEqualTo(4);
        assertThat(updated.availableCapacity()).isEqualTo(6);
        assertThat(updated.cpuUsagePercent()).isEqualTo(25.5);
        assertThat(updated.memoryUsagePercent()).isEqualTo(45.0);
    }

    @Test
    @DisplayName("recordHeartbeat: recovers SUSPECTED worker back to ACTIVE")
    void testHeartbeatRecoversSuspectedWorker() {
        WorkerNode worker = createWorker("worker-4", "session-4", 10);
        registry.registerWorker(worker);

        registry.updateState("worker-4", "session-4", WorkerState.SUSPECTED);
        assertThat(registry.getWorker("worker-4").orElseThrow().state()).isEqualTo(WorkerState.SUSPECTED);

        boolean recorded = registry.recordHeartbeat("worker-4", "session-4", 2, 10.0, 20.0);
        assertThat(recorded).isTrue();

        WorkerNode recovered = registry.getWorker("worker-4").orElseThrow();
        assertThat(recovered.state()).isEqualTo(WorkerState.ACTIVE);
    }

    @Test
    @DisplayName("recordHeartbeat: rejects heartbeat with stale sessionId")
    void testHeartbeatStaleSessionRejected() {
        WorkerNode worker = createWorker("worker-5", "session-current", 10);
        registry.registerWorker(worker);

        boolean recorded = registry.recordHeartbeat("worker-5", "session-old-stale", 1, 5.0, 10.0);
        assertThat(recorded).isFalse();

        WorkerNode current = registry.getWorker("worker-5").orElseThrow();
        assertThat(current.activeTasks()).isEqualTo(0);
    }

    @Test
    @DisplayName("updateState: enforces session check and transitions state")
    void testStateTransitions() {
        WorkerNode worker = createWorker("worker-6", "session-6", 10);
        registry.registerWorker(worker);

        // Valid session
        boolean updated = registry.updateState("worker-6", "session-6", WorkerState.SUSPECTED);
        assertThat(updated).isTrue();
        assertThat(registry.getWorker("worker-6").orElseThrow().state()).isEqualTo(WorkerState.SUSPECTED);

        // Stale session rejected
        boolean staleUpdate = registry.updateState("worker-6", "session-wrong", WorkerState.REMOVED);
        assertThat(staleUpdate).isFalse();
        assertThat(registry.getWorker("worker-6").orElseThrow().state()).isEqualTo(WorkerState.SUSPECTED);
    }

    @Test
    @DisplayName("removeWorker: marks worker as REMOVED and excludes from active list")
    void testRemoveWorker() {
        WorkerNode worker = createWorker("worker-7", "session-7", 10);
        registry.registerWorker(worker);

        Optional<WorkerNode> removed = registry.removeWorker("worker-7", "session-7");
        assertThat(removed).isPresent();
        assertThat(removed.get().state()).isEqualTo(WorkerState.REMOVED);

        assertThat(registry.getActiveWorkers()).isEmpty();
        assertThat(registry.getWorkersByState(WorkerState.REMOVED)).hasSize(1);
    }

    @Test
    @DisplayName("Concurrency: concurrent registrations, heartbeats, and queries execute without data corruption")
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < 5; i++) {
            registry.registerWorker(createWorker("worker-c-" + i, "session-c-" + i, 8));
        }

        AtomicInteger heartbeatsRecorded = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int op = 0; op < operationsPerThread; op++) {
                        String targetWorker = "worker-c-" + (op % 5);
                        String session = "session-c-" + (op % 5);
                        if (registry.recordHeartbeat(targetWorker, session, op % 8, 10.0, 20.0)) {
                            heartbeatsRecorded.incrementAndGet();
                        }
                        registry.getActiveWorkers();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(heartbeatsRecorded.get()).isPositive();
        assertThat(registry.getActiveWorkers()).hasSize(5);
    }
}
