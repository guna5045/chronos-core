package com.chronoscore.coordinator.cluster.health;

import com.chronoscore.coordinator.cluster.config.ClusterProperties;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import com.chronoscore.coordinator.cluster.registry.InMemoryWorkerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerHealthMonitorTest {

    private InMemoryWorkerRegistry registry;
    private ClusterProperties properties;
    private WorkerHealthMonitor healthMonitor;

    @BeforeEach
    void setUp() {
        registry = new InMemoryWorkerRegistry();
        properties = new ClusterProperties();
        properties.getCluster().setHeartbeatTimeout(Duration.ofSeconds(3));
        properties.getCluster().setEvictionTimeout(Duration.ofSeconds(6));
        properties.getCluster().setHealthCheckInterval(Duration.ofSeconds(1));
        properties.getCluster().setEnabled(true);

        healthMonitor = new WorkerHealthMonitor(registry, properties);
    }

    private WorkerNode createWorker(String workerId, String sessionId, Instant lastHeartbeat) {
        return new WorkerNode(
                workerId,
                "node-" + workerId,
                "127.0.0.1",
                9091,
                8,
                0,
                Set.of("DEFAULT"),
                1001L,
                "0.1.0-SNAPSHOT",
                sessionId,
                WorkerState.ACTIVE,
                Instant.now(),
                lastHeartbeat,
                0.0,
                0.0
        );
    }

    @Test
    @DisplayName("Worker with recent heartbeats remains in ACTIVE state")
    void testHealthyWorkerRemainsActive() {
        WorkerNode worker = createWorker("worker-healthy", "sess-1", Instant.now());
        registry.registerWorker(worker);

        healthMonitor.checkHealthNow();

        WorkerNode retrieved = registry.getWorker("worker-healthy").orElseThrow();
        assertThat(retrieved.state()).isEqualTo(WorkerState.ACTIVE);
    }

    @Test
    @DisplayName("Worker exceeding 3s heartbeat threshold transitions from ACTIVE to SUSPECTED")
    void testMissedHeartbeatsTransitionsToSuspected() {
        Instant fourSecondsAgo = Instant.now().minusSeconds(4);
        WorkerNode worker = createWorker("worker-stale", "sess-2", fourSecondsAgo);
        registry.registerWorker(worker);

        healthMonitor.checkHealthNow();

        WorkerNode retrieved = registry.getWorker("worker-stale").orElseThrow();
        assertThat(retrieved.state()).isEqualTo(WorkerState.SUSPECTED);
    }

    @Test
    @DisplayName("SUSPECTED worker with continued absence exceeding 6s transitions to REMOVED")
    void testContinuedAbsenceTransitionsToRemoved() {
        Instant sevenSecondsAgo = Instant.now().minusSeconds(7);
        WorkerNode worker = createWorker("worker-dead", "sess-3", sevenSecondsAgo);
        registry.registerWorker(worker);

        // First check transitions to SUSPECTED
        healthMonitor.checkHealthNow();
        assertThat(registry.getWorker("worker-dead").orElseThrow().state()).isEqualTo(WorkerState.SUSPECTED);

        // Second check with > 6s elapsed transitions to REMOVED
        healthMonitor.checkHealthNow();
        assertThat(registry.getWorker("worker-dead").orElseThrow().state()).isEqualTo(WorkerState.REMOVED);
    }

    @Test
    @DisplayName("Multi-worker failure isolation: failure of Worker A does not affect Worker B and Worker C")
    void testMultiWorkerFailureIsolation() {
        Instant now = Instant.now();
        WorkerNode workerA = createWorker("worker-A", "sess-A", now.minusSeconds(5)); // Missed heartbeats
        WorkerNode workerB = createWorker("worker-B", "sess-B", now);                  // Healthy
        WorkerNode workerC = createWorker("worker-C", "sess-C", now);                  // Healthy

        registry.registerWorker(workerA);
        registry.registerWorker(workerB);
        registry.registerWorker(workerC);

        healthMonitor.checkHealthNow();

        assertThat(registry.getWorker("worker-A").orElseThrow().state()).isEqualTo(WorkerState.SUSPECTED);
        assertThat(registry.getWorker("worker-B").orElseThrow().state()).isEqualTo(WorkerState.ACTIVE);
        assertThat(registry.getWorker("worker-C").orElseThrow().state()).isEqualTo(WorkerState.ACTIVE);
        assertThat(registry.getActiveWorkerCount()).isEqualTo(2);
    }
}
