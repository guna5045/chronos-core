package com.chronoscore.coordinator.distribution;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsistentHashWorkerSelectorTest {

    @Mock
    private WorkerRegistry workerRegistry;

    private ConsistentHashRing ring;
    private ConsistentHashWorkerSelector selector;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing(128);
        selector = new ConsistentHashWorkerSelector(ring, workerRegistry);
    }

    private Task createSampleTask(UUID taskId) {
        return Task.builder()
                .taskId(taskId != null ? taskId : UUID.randomUUID())
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
    }

    private WorkerNode createWorker(String workerId, WorkerState state, int maxConcurrency, int activeTasks) {
        Instant now = Instant.now();
        return new WorkerNode(
                workerId,
                "localhost",
                "127.0.0.1",
                9091,
                maxConcurrency,
                activeTasks,
                java.util.Set.of("COMPUTE"),
                1234L,
                "0.1.0-SNAPSHOT",
                UUID.randomUUID().toString(),
                state,
                now,
                now,
                0.0,
                0.0
        );
    }

    @Test
    @DisplayName("Returns empty when no active workers are registered")
    void testNoActiveWorkers() {
        when(workerRegistry.getActiveWorkers()).thenReturn(List.of());

        Task task = createSampleTask(UUID.randomUUID());
        Optional<WorkerNode> selected = selector.selectWorker(task);

        assertThat(selected).isEmpty();
    }

    @Test
    @DisplayName("Selects active worker with available capacity")
    void testSelectsActiveWorkerWithCapacity() {
        WorkerNode node = createWorker("worker-1", WorkerState.ACTIVE, 10, 2);
        when(workerRegistry.getActiveWorkers()).thenReturn(List.of(node));
        when(workerRegistry.getWorker("worker-1")).thenReturn(Optional.of(node));

        Task task = createSampleTask(UUID.randomUUID());
        Optional<WorkerNode> selected = selector.selectWorker(task);

        assertThat(selected).isPresent();
        assertThat(selected.get().workerId()).isEqualTo("worker-1");
    }

    @Test
    @DisplayName("Falls back to next active worker along the ring when primary worker is saturated")
    void testFallbackWhenPrimarySaturated() {
        WorkerNode nodeA = createWorker("worker-A", WorkerState.ACTIVE, 2, 2); // Saturated
        WorkerNode nodeB = createWorker("worker-B", WorkerState.ACTIVE, 4, 1); // Has capacity

        when(workerRegistry.getActiveWorkers()).thenReturn(List.of(nodeA, nodeB));
        when(workerRegistry.getWorker("worker-A")).thenReturn(Optional.of(nodeA));
        when(workerRegistry.getWorker("worker-B")).thenReturn(Optional.of(nodeB));

        // Find a task that initially maps to worker-A
        Task task = null;
        for (int i = 0; i < 100; i++) {
            UUID id = UUID.randomUUID();
            ring.reconcile(List.of("worker-A", "worker-B"));
            if ("worker-A".equals(ring.getWorker(id.toString()).orElse(null))) {
                task = createSampleTask(id);
                break;
            }
        }
        assertThat(task).isNotNull();

        Optional<WorkerNode> selected = selector.selectWorker(task);

        assertThat(selected).isPresent();
        // Since worker-A was saturated, fallback MUST select worker-B
        assertThat(selected.get().workerId()).isEqualTo("worker-B");
    }

    @Test
    @DisplayName("Applies backpressure and returns empty when all active candidate workers are saturated")
    void testBackpressureWhenAllSaturated() {
        WorkerNode nodeA = createWorker("worker-A", WorkerState.ACTIVE, 2, 2); // Saturated
        WorkerNode nodeB = createWorker("worker-B", WorkerState.ACTIVE, 2, 2); // Saturated

        when(workerRegistry.getActiveWorkers()).thenReturn(List.of(nodeA, nodeB));
        when(workerRegistry.getWorker("worker-A")).thenReturn(Optional.of(nodeA));
        when(workerRegistry.getWorker("worker-B")).thenReturn(Optional.of(nodeB));

        Task task = createSampleTask(UUID.randomUUID());
        Optional<WorkerNode> selected = selector.selectWorker(task);

        // All saturated -> controlled admission backpressure
        assertThat(selected).isEmpty();
    }
}
