package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for {@link Task} domain entities.
 * <p>
 * Kept independent of ORM and framework-specific abstractions to maintain
 * clean architectural separation.
 */
public interface TaskRepository {

    /**
     * Persists a newly submitted task.
     *
     * @param task task entity to persist.
     * @return persisted task entity.
     * @throws com.chronoscore.coordinator.persistence.exception.DuplicateTaskException if idempotency or primary key collision occurs.
     */
    Task save(Task task);

    /**
     * Finds a task by its unique identifier.
     *
     * @param taskId task UUID.
     * @return Optional containing the task if found, or empty.
     */
    Optional<Task> findById(UUID taskId);

    /**
     * Finds a task by ID acquiring an exclusive row-level lock ({@code SELECT FOR UPDATE}).
     *
     * @param taskId task UUID.
     * @return Optional containing the locked task if found, or empty.
     */
    Optional<Task> findByIdForUpdate(UUID taskId);

    /**
     * Updates an existing task using optimistic concurrency version protection.
     *
     * @param task task entity with mutated state and expected version.
     * @return updated task entity with incremented version.
     * @throws com.chronoscore.coordinator.persistence.exception.OptimisticLockingException if the row version does not match.
     * @throws com.chronoscore.coordinator.persistence.exception.TaskNotFoundException if the task does not exist.
     */
    Task update(Task task);

    /**
     * Finds a task by tenant ID and non-null idempotency key.
     *
     * @param tenantId       tenant partition key.
     * @param idempotencyKey client-supplied idempotency key.
     * @return Optional containing the task if found, or empty.
     */
    Optional<Task> findByIdempotencyKey(String tenantId, String idempotencyKey);

    /**
     * Checks if a task exists with the given ID.
     *
     * @param taskId task UUID.
     * @return true if exists, false otherwise.
     */
    boolean existsById(UUID taskId);

    /**
     * Retrieves ready QUEUED tasks ordered by priority and schedule time.
     *
     * @param limit maximum number of tasks to retrieve.
     * @return list of queued tasks.
     */
    List<Task> findQueuedTasks(int limit);

    /**
     * Retrieves schedulable QUEUED tasks whose scheduledAt is null or in the past (<= asOf),
     * ordered by priority (CRITICAL > HIGH > NORMAL > LOW), scheduledAt ASC, and createdAt ASC.
     *
     * @param asOf timestamp threshold for scheduled tasks.
     * @param limit maximum number of tasks to return.
     * @return list of schedulable tasks ready for admission.
     */
    List<Task> findSchedulableTasks(Instant asOf, int limit);

    /**
     * Retrieves starved QUEUED tasks that have been waiting in queue longer than maxWaitTime
     * (createdAt <= asOf - maxWaitTime), ordered by oldest created first.
     *
     * @param asOf current timestamp baseline.
     * @param maxWaitTime wait duration threshold beyond which a task is considered starved.
     * @param limit maximum number of starved tasks to return.
     * @return list of starved tasks.
     */
    List<Task> findStarvedTasks(Instant asOf, Duration maxWaitTime, int limit);

    /**
     * Retrieves tasks in a specific lifecycle status ordered by createdAt ASC.
     *
     * @param status lifecycle status to filter by (e.g. DLQ, RUNNING).
     * @param limit maximum number of tasks to return.
     * @return list of matching tasks.
     */
    List<Task> findByStatus(TaskStatus status, int limit);

    /**
     * Counts tasks for a given tenant in a specific lifecycle status.
     *
     * @param tenantId tenant identifier.
     * @param status   lifecycle status.
     * @return count of matching tasks.
     */
    default int countByTenantAndStatus(String tenantId, TaskStatus status) {
        return 0;
    }

    /**
     * Counts active running tasks (status DISPATCHED or RUNNING) for a given tenant.
     * Used for tenant concurrency quota checks and database reconciliation.
     *
     * @param tenantId tenant identifier.
     * @return count of active tasks currently executing.
     */
    default int countRunningByTenant(String tenantId) {
        return 0;
    }

    /**
     * Retrieves tasks for a given tenant in a specific status.
     *
     * @param tenantId tenant identifier.
     * @param status   lifecycle status.
     * @param limit    maximum number of tasks.
     * @return list of matching tasks.
     */
    default List<Task> findByTenantAndStatus(String tenantId, TaskStatus status, int limit) {
        return java.util.Collections.emptyList();
    }

    /**
     * Counts active tasks (DISPATCHED or RUNNING) for a given tenant with a specific logical concurrency key.
     *
     * @param tenantId       tenant identifier.
     * @param concurrencyKey logical concurrency group key.
     * @return count of currently executing tasks with this concurrency key.
     */
    default int countActiveByConcurrencyKey(String tenantId, String concurrencyKey) {
        return 0;
    }

    /**
     * Searches tasks applying multi-attribute filtering and pagination.
     */
    default List<Task> findTasksWithFilter(String tenantId, TaskStatus status, String taskType, com.chronoscore.common.enums.TaskPriority priority, int limit, int offset) {
        return java.util.Collections.emptyList();
    }

    /**
     * Counts tasks matching multi-attribute filter.
     */
    default int countTasksWithFilter(String tenantId, TaskStatus status, String taskType, com.chronoscore.common.enums.TaskPriority priority) {
        return 0;
    }
}

