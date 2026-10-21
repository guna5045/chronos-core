package com.chronoscore.coordinator.recovery.lease;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for durable {@link TaskExecutionLease} records.
 */
public interface TaskLeaseRepository {

    /**
     * Persists or replaces an execution lease for a task.
     *
     * @param lease execution lease entity.
     * @return persisted lease.
     */
    TaskExecutionLease save(TaskExecutionLease lease);

    /**
     * Atomically extends the expiration time of an existing lease.
     *
     * @param taskId       task UUID.
     * @param newExpiresAt new lease expiration timestamp.
     * @return true if updated, false if not found.
     */
    boolean renewLease(UUID taskId, Instant newExpiresAt);

    /**
     * Removes the execution lease for a completed, failed, or cancelled task.
     *
     * @param taskId task UUID.
     * @return true if released, false if no lease existed.
     */
    boolean releaseLease(UUID taskId);

    /**
     * Retrieves the active lease for a task.
     *
     * @param taskId task UUID.
     * @return Optional containing the lease if present, or empty.
     */
    Optional<TaskExecutionLease> findByTaskId(UUID taskId);

    /**
     * Finds all active leases held by a specific worker.
     *
     * @param workerId worker node identifier.
     * @return list of active leases for the worker.
     */
    List<TaskExecutionLease> findByWorkerId(String workerId);

    /**
     * Finds expired execution leases as of the specified instant, using FOR UPDATE SKIP LOCKED
     * to allow safe multi-coordinator recovery polling without blocking.
     *
     * @param asOf  cutoff instant.
     * @param limit maximum batch size.
     * @return list of expired leases.
     */
    List<TaskExecutionLease> findExpiredLeases(Instant asOf, int limit);
}
