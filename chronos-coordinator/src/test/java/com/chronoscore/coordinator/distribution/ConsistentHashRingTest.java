package com.chronoscore.coordinator.distribution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistentHashRingTest {

    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing(128);
    }

    @Test
    @DisplayName("Empty hash ring safely returns empty optional for worker lookup")
    void testEmptyRing() {
        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.size()).isEqualTo(0);
        assertThat(ring.virtualNodeCount()).isEqualTo(0);

        Optional<String> worker = ring.getWorker("task-123");
        assertThat(worker).isEmpty();

        List<String> candidates = ring.getCandidates("task-123");
        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("Each worker receives exactly the configured number of virtual nodes")
    void testVirtualNodeAllocation() {
        ring.addWorker("worker-alpha");
        ring.addWorker("worker-beta");

        assertThat(ring.size()).isEqualTo(2);
        assertThat(ring.virtualNodeCount()).isEqualTo(256);

        Set<Long> alphaNodes = ring.getVirtualNodes("worker-alpha");
        Set<Long> betaNodes = ring.getVirtualNodes("worker-beta");

        assertThat(alphaNodes).hasSize(128);
        assertThat(betaNodes).hasSize(128);
        assertThat(alphaNodes).doesNotContainAnyElementsOf(betaNodes);
    }

    @Test
    @DisplayName("Worker selection is deterministic given the same task ID and membership")
    void testDeterminism() {
        ring.addWorker("worker-1");
        ring.addWorker("worker-2");
        ring.addWorker("worker-3");

        String taskId = "task-uuid-" + UUID.randomUUID();

        Optional<String> initialLookup = ring.getWorker(taskId);
        assertThat(initialLookup).isPresent();

        // 100 consecutive lookups must yield identical result
        for (int i = 0; i < 100; i++) {
            Optional<String> lookup = ring.getWorker(taskId);
            assertThat(lookup).isEqualTo(initialLookup);
        }
    }

    @Test
    @DisplayName("Adding a worker remaps only a fraction of existing tasks")
    void testWorkerAdditionMinimalRemapping() {
        ring.addWorker("worker-A");
        ring.addWorker("worker-B");
        ring.addWorker("worker-C");

        int sampleSize = 1000;
        List<String> taskIds = new ArrayList<>(sampleSize);
        Map<String, String> initialAssignments = new HashMap<>(sampleSize);

        for (int i = 0; i < sampleSize; i++) {
            String taskId = "task-" + i;
            taskIds.add(taskId);
            initialAssignments.put(taskId, ring.getWorker(taskId).orElseThrow());
        }

        // Add 4th worker
        ring.addWorker("worker-D");

        int remappedCount = 0;
        for (String taskId : taskIds) {
            String newWorker = ring.getWorker(taskId).orElseThrow();
            if (!newWorker.equals(initialAssignments.get(taskId))) {
                remappedCount++;
                // Crucial invariant: remapped tasks MUST now point to the newly added worker
                assertThat(newWorker).isEqualTo("worker-D");
            }
        }

        double remappedPercentage = (double) remappedCount / sampleSize * 100.0;
        // Theoretical remapping for 3 -> 4 workers is ~25%. Allow reasonable statistical bounds [15%, 35%]
        assertThat(remappedPercentage).isBetween(15.0, 35.0);
    }

    @Test
    @DisplayName("Removing a worker remaps only tasks that belonged to the removed worker")
    void testWorkerRemovalRemapping() {
        ring.addWorker("worker-1");
        ring.addWorker("worker-2");
        ring.addWorker("worker-3");

        int sampleSize = 1000;
        List<String> taskIds = new ArrayList<>(sampleSize);
        Map<String, String> initialAssignments = new HashMap<>(sampleSize);

        for (int i = 0; i < sampleSize; i++) {
            String taskId = "sample-task-" + i;
            taskIds.add(taskId);
            initialAssignments.put(taskId, ring.getWorker(taskId).orElseThrow());
        }

        // Remove worker-2
        ring.removeWorker("worker-2");

        for (String taskId : taskIds) {
            String originalWorker = initialAssignments.get(taskId);
            String currentWorker = ring.getWorker(taskId).orElseThrow();

            if (originalWorker.equals("worker-2")) {
                // Must be remapped to remaining workers
                assertThat(currentWorker).isIn("worker-1", "worker-3");
            } else {
                // Invariant: tasks assigned to worker-1 or worker-3 must NOT be disturbed
                assertThat(currentWorker).isEqualTo(originalWorker);
            }
        }
    }

    @Test
    @DisplayName("Distribution across workers is reasonably balanced")
    void testDistributionBalance() {
        int workerCount = 5;
        for (int i = 0; i < workerCount; i++) {
            ring.addWorker("worker-" + i);
        }

        int sampleSize = 10000;
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < sampleSize; i++) {
            String taskId = UUID.randomUUID().toString();
            String assignedWorker = ring.getWorker(taskId).orElseThrow();
            counts.merge(assignedWorker, 1, Integer::sum);
        }

        assertThat(counts).hasSize(workerCount);

        // Expected mean is 2,000 (20%). With 128 virtual nodes, each worker should receive between 12% and 28%
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            double percentage = (double) entry.getValue() / sampleSize * 100.0;
            assertThat(percentage).isBetween(12.0, 28.0);
        }
    }

    @Test
    @DisplayName("getCandidates returns ordered distinct candidates wrapping around the ring")
    void testCandidateWrapAround() {
        ring.addWorker("node-A");
        ring.addWorker("node-B");
        ring.addWorker("node-C");

        String taskId = UUID.randomUUID().toString();
        List<String> candidates = ring.getCandidates(taskId);

        assertThat(candidates).hasSize(3);
        assertThat(candidates).containsExactlyInAnyOrder("node-A", "node-B", "node-C");
        // Primary candidate must equal getWorker result
        assertThat(candidates.get(0)).isEqualTo(ring.getWorker(taskId).orElseThrow());
    }

    @Test
    @DisplayName("Reconciliation cleanly updates active workers without recreation")
    void testReconciliation() {
        ring.addWorker("node-1");
        ring.addWorker("node-2");

        // Reconcile with node-2 and node-3
        ring.reconcile(List.of("node-2", "node-3"));

        assertThat(ring.getWorkers()).containsExactlyInAnyOrder("node-2", "node-3");
        assertThat(ring.virtualNodeCount()).isEqualTo(256);
    }
}
