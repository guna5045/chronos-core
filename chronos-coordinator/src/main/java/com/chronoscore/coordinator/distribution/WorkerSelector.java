package com.chronoscore.coordinator.distribution;

import com.chronoscore.common.domain.Task;
import com.chronoscore.coordinator.cluster.model.WorkerNode;

import java.util.Optional;

/**
 * Strategy interface for selecting an eligible, active worker node to receive a task dispatch.
 */
public interface WorkerSelector {

    /**
     * Selects an eligible worker for the specified task based on consistent hashing
     * and current capacity availability.
     *
     * @param task the task to be dispatched.
     * @return Optional containing the selected {@link WorkerNode}, or empty if no eligible or
     *         un-saturated worker is available.
     */
    Optional<WorkerNode> selectWorker(Task task);
}
