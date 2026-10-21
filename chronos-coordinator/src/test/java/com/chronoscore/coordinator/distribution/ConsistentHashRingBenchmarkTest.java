package com.chronoscore.coordinator.distribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistentHashRingBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(ConsistentHashRingBenchmarkTest.class);

    @Test
    @DisplayName("Evaluate hash ring distribution and remapping metrics with measured values")
    void benchmarkConsistentHashRingDistributionAndRemapping() {
        int initialWorkerCount = 5;
        int virtualNodes = 128;
        int sampleSize = 10000;

        ConsistentHashRing ring = new ConsistentHashRing(virtualNodes);
        for (int i = 0; i < initialWorkerCount; i++) {
            ring.addWorker("worker-" + i);
        }

        // 1. Measure initial distribution across 10,000 tasks
        List<String> taskIds = new ArrayList<>(sampleSize);
        Map<String, String> initialMapping = new HashMap<>(sampleSize);
        Map<String, Integer> distribution = new HashMap<>();

        for (int i = 0; i < sampleSize; i++) {
            String taskId = UUID.randomUUID().toString();
            taskIds.add(taskId);
            String assignedWorker = ring.getWorker(taskId).orElseThrow();
            initialMapping.put(taskId, assignedWorker);
            distribution.merge(assignedWorker, 1, Integer::sum);
        }

        // Print measured distribution
        log.info("=== CONSISTENT HASH RING MEASURED BENCHMARK ===");
        log.info("Initial Workers: {}", initialWorkerCount);
        log.info("Virtual Nodes Per Worker: {}", virtualNodes);
        log.info("Total Virtual Nodes: {}", ring.virtualNodeCount());
        log.info("Sampled Tasks: {}", sampleSize);
        distribution.forEach((worker, count) -> {
            double pct = (double) count / sampleSize * 100.0;
            log.info("  Worker '{}': {} tasks ({}%)", worker, count, String.format("%.2f", pct));
        });

        // 2. Measure remapping on Worker Addition (5 -> 6 workers)
        ring.addWorker("worker-added-5");
        int remappedOnAddition = 0;
        for (String taskId : taskIds) {
            String newWorker = ring.getWorker(taskId).orElseThrow();
            if (!newWorker.equals(initialMapping.get(taskId))) {
                remappedOnAddition++;
            }
        }
        double additionRemappedPct = (double) remappedOnAddition / sampleSize * 100.0;
        log.info("Key Remapping After Worker Addition: {} / {} ({}%)",
                remappedOnAddition, sampleSize, String.format("%.2f", additionRemappedPct));

        // 3. Measure remapping on Worker Removal (remove worker-added-5)
        ring.removeWorker("worker-added-5");
        int remappedOnRemoval = 0;
        for (String taskId : taskIds) {
            String currentWorker = ring.getWorker(taskId).orElseThrow();
            if (!currentWorker.equals(initialMapping.get(taskId))) {
                remappedOnRemoval++;
            }
        }
        double removalRemappedPct = (double) remappedOnRemoval / sampleSize * 100.0;
        log.info("Key Remapping After Worker Removal (revert to original): {} / {} ({}%)",
                remappedOnRemoval, sampleSize, String.format("%.2f", removalRemappedPct));

        assertThat(distribution).hasSize(initialWorkerCount);
        // Theoretical remapping for 1/6 is ~16.67%
        assertThat(additionRemappedPct).isBetween(10.0, 25.0);
        // After removing the newly added worker, all keys must return to their exact original mapping
        assertThat(remappedOnRemoval).isEqualTo(0);
    }
}
