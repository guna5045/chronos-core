package com.chronoscore.coordinator.cluster.registry;

import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;

import java.util.List;
import java.util.Optional;

/**
 * Thread-safe in-memory registry of worker nodes managed by the active Coordinator instance.
 */
public interface WorkerRegistry {

    /**
     * Registers or re-registers a worker node in the registry.
     * Overwrites any prior session for the same worker ID.
     *
     * @param workerNode the worker node to register
     * @return the registered WorkerNode snapshot
     */
    WorkerNode registerWorker(WorkerNode workerNode);

    /**
     * Retrieves a worker node by its unique worker ID.
     *
     * @param workerId worker identifier
     * @return Optional containing WorkerNode if present
     */
    Optional<WorkerNode> getWorker(String workerId);

    /**
     * Returns an unmodifiable snapshot list of all registered workers regardless of state.
     */
    List<WorkerNode> getAllWorkers();

    /**
     * Returns an unmodifiable snapshot list of all workers currently in the ACTIVE state.
     */
    List<WorkerNode> getActiveWorkers();

    /**
     * Returns an unmodifiable snapshot list of workers matching the specified lifecycle state.
     *
     * @param state desired WorkerState
     */
    List<WorkerNode> getWorkersByState(WorkerState state);

    /**
     * Records a heartbeat ping from a worker node.
     * Verifies that the provided sessionId matches the active registered session.
     * If the worker was in SUSPECTED state, it is transitioned back to ACTIVE.
     *
     * @param workerId worker identifier
     * @param sessionId connection session UUID
     * @param activeTasks current concurrent tasks reported by worker
     * @param cpu CPU utilization percent
     * @param memory Memory utilization percent
     * @return true if heartbeat was accepted and recorded, false if worker unknown or sessionId stale
     */
    boolean recordHeartbeat(String workerId, String sessionId, int activeTasks, double cpu, double memory);

    /**
     * Atomically transitions the worker's lifecycle state if the sessionId matches.
     *
     * @param workerId worker identifier
     * @param sessionId connection session UUID
     * @param newState target WorkerState
     * @return true if updated, false if worker not found or session stale
     */
    boolean updateState(String workerId, String sessionId, WorkerState newState);

    /**
     * Removes a worker node from the cluster registry if the sessionId matches.
     *
     * @param workerId worker identifier
     * @param sessionId connection session UUID
     * @return Optional containing the removed WorkerNode if removed
     */
    Optional<WorkerNode> removeWorker(String workerId, String sessionId);

    /**
     * Returns the number of currently ACTIVE workers.
     */
    int getActiveWorkerCount();

    /**
     * Clears all workers from the registry.
     */
    void clear();
}
