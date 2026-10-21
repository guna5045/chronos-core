package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.common.domain.ExecutionAttempt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for physical {@link ExecutionAttempt} records.
 * <p>
 * Preserves the full execution history for every task, supporting failure diagnosis,
 * retry tracking, and worker operational monitoring.
 */
public interface ExecutionAttemptRepository {

    /**
     * Persists a new execution attempt record.
     *
     * @param attempt execution attempt entity.
     * @return persisted attempt.
     */
    ExecutionAttempt save(ExecutionAttempt attempt);

    /**
     * Updates an existing execution attempt (e.g., setting completion status, error message).
     *
     * @param attempt execution attempt entity with updated completion info.
     * @return updated attempt.
     */
    ExecutionAttempt update(ExecutionAttempt attempt);

    /**
     * Finds an execution attempt by its unique attempt UUID.
     *
     * @param attemptId attempt UUID.
     * @return Optional containing the attempt if found, or empty.
     */
    Optional<ExecutionAttempt> findById(UUID attemptId);

    /**
     * Retrieves all execution attempts for a given task, ordered by attempt number ascending.
     *
     * @param taskId task UUID.
     * @return list of attempts.
     */
    List<ExecutionAttempt> findByTaskId(UUID taskId);

    /**
     * Finds the latest (highest attempt number) execution attempt for a given task.
     *
     * @param taskId task UUID.
     * @return Optional containing the latest attempt if found, or empty.
     */
    Optional<ExecutionAttempt> findLatestAttempt(UUID taskId);

    /**
     * Finds a specific execution attempt by task ID and attempt number.
     *
     * @param taskId        task UUID.
     * @param attemptNumber physical attempt sequence number (1, 2, ...).
     * @return Optional containing the attempt if found, or empty.
     */
    Optional<ExecutionAttempt> findByTaskIdAndAttemptNumber(UUID taskId, int attemptNumber);
}
