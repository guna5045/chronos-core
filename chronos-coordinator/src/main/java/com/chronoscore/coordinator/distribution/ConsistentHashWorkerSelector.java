package com.chronoscore.coordinator.distribution;

import com.chronoscore.common.domain.Task;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consistent hash ring-based worker selector enforcing node liveness and capacity constraints.
 * <p>
 * <b>Selection Flow:</b>
 * <ol>
 *   <li>Reconciles the hash ring to reflect currently {@code ACTIVE} workers from {@link WorkerRegistry}.
 *       Workers in {@code SUSPECTED} or {@code REMOVED} states are strictly excluded.</li>
 *   <li>Computes the deterministic ring position for {@code taskId} and identifies candidate workers.</li>
 *   <li>Evaluates candidate workers in topological ring order: selects the first worker that is
 *       both {@code ACTIVE} and has available capacity ({@code activeTasks < maxConcurrency}).</li>
 *   <li>If all candidate workers are saturated, returns {@link Optional#empty()} to trigger
 *       controlled admission backpressure without task dropping.</li>
 * </ol>
 */
@Component
public class ConsistentHashWorkerSelector implements WorkerSelector {

    private static final Logger log = LoggerFactory.getLogger(ConsistentHashWorkerSelector.class);

    private final ConsistentHashRing consistentHashRing;
    private final WorkerRegistry workerRegistry;

    public ConsistentHashWorkerSelector(ConsistentHashRing consistentHashRing,
                                        WorkerRegistry workerRegistry) {
        this.consistentHashRing = Objects.requireNonNull(consistentHashRing, "consistentHashRing must not be null");
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry must not be null");
    }

    @Override
    public Optional<WorkerNode> selectWorker(Task task) {
        Objects.requireNonNull(task, "task must not be null");

        // 1. Retrieve all active workers currently eligible for dispatch
        List<WorkerNode> activeWorkers = workerRegistry.getActiveWorkers();
        if (activeWorkers.isEmpty()) {
            log.debug("No ACTIVE workers registered; task '{}' cannot be assigned", task.getTaskId());
            return Optional.empty();
        }

        // 2. Reconcile ring membership with current active workers
        Set<String> activeWorkerIds = activeWorkers.stream()
                .map(WorkerNode::workerId)
                .collect(Collectors.toSet());
        consistentHashRing.reconcile(activeWorkerIds);

        // 3. Find candidates starting at the deterministic hash position and traversing along the ring
        List<String> candidates = consistentHashRing.getCandidates(task.getTaskId().toString());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // 4. Evaluate candidates for liveness and available capacity
        for (String candidateId : candidates) {
            Optional<WorkerNode> nodeOpt = workerRegistry.getWorker(candidateId);
            if (nodeOpt.isPresent()) {
                WorkerNode node = nodeOpt.get();
                if (node.state() == WorkerState.ACTIVE && node.hasAvailableCapacity()) {
                    log.debug("Selected worker '{}' (active: {}/{}) for task '{}'",
                            node.workerId(), node.activeTasks(), node.maxConcurrency(), task.getTaskId());
                    return Optional.of(node);
                } else if (node.state() == WorkerState.ACTIVE) {
                    log.debug("Candidate worker '{}' is saturated ({}/{}); checking fallback",
                            node.workerId(), node.activeTasks(), node.maxConcurrency());
                }
            }
        }

        log.debug("All {} candidate workers are saturated; backpressure applied for task '{}'",
                candidates.size(), task.getTaskId());
        return Optional.empty();
    }

    public ConsistentHashRing getConsistentHashRing() {
        return consistentHashRing;
    }
}
