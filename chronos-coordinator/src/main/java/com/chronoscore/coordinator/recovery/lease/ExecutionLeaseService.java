package com.chronoscore.coordinator.recovery.lease;

import com.chronoscore.coordinator.recovery.config.LeaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing task execution lease lifecycles.
 */
@Service
public class ExecutionLeaseService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLeaseService.class);

    private final TaskLeaseRepository leaseRepository;
    private final LeaseProperties leaseProperties;

    public ExecutionLeaseService(TaskLeaseRepository leaseRepository, LeaseProperties leaseProperties) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository must not be null");
        this.leaseProperties = Objects.requireNonNull(leaseProperties, "leaseProperties must not be null");
    }

    /**
     * Acquires an execution lease for a newly dispatched or running task attempt.
     *
     * @param taskId        task UUID.
     * @param attemptId     physical attempt UUID.
     * @param workerId      executing worker node identifier.
     * @param attemptNumber physical attempt number.
     * @param leaderEpoch   current leader epoch fencing token.
     * @return persisted execution lease.
     */
    public TaskExecutionLease acquireLease(UUID taskId, UUID attemptId, String workerId, int attemptNumber, long leaderEpoch) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(workerId, "workerId must not be null");

        Instant now = Instant.now();
        Instant expiresAt = now.plus(leaseProperties.getDuration());

        TaskExecutionLease lease = TaskExecutionLease.builder()
                .taskId(taskId)
                .attemptId(attemptId)
                .workerId(workerId)
                .attemptNumber(attemptNumber)
                .leaseAcquiredAt(now)
                .leaseExpiresAt(expiresAt)
                .leaderEpoch(leaderEpoch)
                .version(0L)
                .build();

        TaskExecutionLease saved = leaseRepository.save(lease);
        log.debug("Acquired execution lease for task '{}' (attempt #{}, worker '{}', expires: {})",
                taskId, attemptNumber, workerId, expiresAt);
        return saved;
    }

    /**
     * Renews the execution lease for an active task.
     *
     * @param taskId task UUID.
     * @return true if renewed, false if no active lease exists.
     */
    public boolean renewLease(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Instant newExpiresAt = Instant.now().plus(leaseProperties.getDuration());
        boolean renewed = leaseRepository.renewLease(taskId, newExpiresAt);
        if (renewed) {
            log.trace("Renewed execution lease for task '{}' to {}", taskId, newExpiresAt);
        } else {
            log.warn("Failed to renew execution lease for task '{}': lease not found or already released", taskId);
        }
        return renewed;
    }

    /**
     * Releases the execution lease when a task completes or reaches a terminal state.
     *
     * @param taskId task UUID.
     * @return true if released, false if already absent.
     */
    public boolean releaseLease(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        boolean released = leaseRepository.releaseLease(taskId);
        if (released) {
            log.debug("Released execution lease for task '{}'", taskId);
        }
        return released;
    }

    /**
     * Retrieves the active lease for a task if present.
     *
     * @param taskId task UUID.
     * @return Optional containing the lease, or empty.
     */
    public Optional<TaskExecutionLease> getLease(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return leaseRepository.findByTaskId(taskId);
    }

    /**
     * Finds all active leases held by a specific worker.
     *
     * @param workerId worker node identifier.
     * @return list of active leases.
     */
    public List<TaskExecutionLease> getLeasesForWorker(String workerId) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        return leaseRepository.findByWorkerId(workerId);
    }

    /**
     * Finds expired leases as of the cutoff instant.
     *
     * @param asOf  cutoff instant.
     * @param limit maximum batch size.
     * @return list of expired leases.
     */
    public List<TaskExecutionLease> getExpiredLeases(Instant asOf, int limit) {
        return leaseRepository.findExpiredLeases(asOf, limit);
    }
}
