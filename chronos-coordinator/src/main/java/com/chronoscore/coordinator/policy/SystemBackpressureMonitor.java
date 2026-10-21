package com.chronoscore.coordinator.policy;

import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates cluster-wide operational saturation and backpressure signals.
 * <p>
 * Signals Evaluated:
 * <ul>
 *   <li><b>Worker Capacity Saturation</b>: If all registered active workers have zero remaining capacity,
 *       admission is suspended to avoid dispatch failures.</li>
 *   <li><b>Zero Registered Workers</b>: If no workers are active in the cluster, tasks remain safely queued.</li>
 * </ul>
 */
@Component
public class SystemBackpressureMonitor {

    private final WorkerRegistry workerRegistry;

    @Autowired
    public SystemBackpressureMonitor(@Autowired(required = false) WorkerRegistry workerRegistry) {
        this.workerRegistry = workerRegistry;
    }

    /**
     * Checks if the cluster is under severe backpressure.
     *
     * @return true if under backpressure, false if system has operational capacity.
     */
    public boolean isUnderBackpressure() {
        if (workerRegistry == null) {
            return false;
        }

        List<WorkerNode> activeWorkers = workerRegistry.getActiveWorkers();
        if (activeWorkers.isEmpty()) {
            return true;
        }

        int totalAvailableCapacity = 0;
        for (WorkerNode worker : activeWorkers) {
            totalAvailableCapacity += worker.availableCapacity();
        }

        return totalAvailableCapacity <= 0;
    }

    /**
     * Calculates the total remaining execution capacity across all active workers.
     *
     * @return total available worker capacity.
     */
    public int getTotalAvailableCapacity() {
        if (workerRegistry == null) {
            return Integer.MAX_VALUE;
        }
        int total = 0;
        for (WorkerNode worker : workerRegistry.getActiveWorkers()) {
            total += worker.availableCapacity();
        }
        return total;
    }
}
