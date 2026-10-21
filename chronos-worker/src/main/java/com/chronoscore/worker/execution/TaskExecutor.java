package com.chronoscore.worker.execution;

import com.chronoscore.common.proto.ProtoTaskAssignment;

import java.util.concurrent.CompletableFuture;

/**
 * Worker execution abstraction responsible for accepting task assignments,
 * enforcing worker concurrency quotas, and executing task attempts.
 */
public interface TaskExecutor {

    /**
     * Submits an assigned task for execution.
     *
     * @param assignment protobuf task assignment from coordinator
     * @return CompletableFuture completing with the physical TaskExecutionResult
     * @throws CapacityExhaustedException if activeTasks &gt;= maxConcurrency
     */
    CompletableFuture<TaskExecutionResult> executeTask(ProtoTaskAssignment assignment);

    /**
     * Cancels an actively running task attempt.
     *
     * @param taskId task identifier to cancel
     * @param reason cancellation rationale
     * @return true if task was found and cancellation requested
     */
    boolean cancelTask(String taskId, String reason);

    /**
     * Returns the current number of active running tasks on this worker.
     */
    int getActiveTaskCount();

    /**
     * Returns the configured maximum concurrency capacity of this worker.
     */
    int getMaxConcurrency();

    /**
     * Returns true if activeTaskCount &lt; maxConcurrency.
     */
    boolean hasAvailableCapacity();

    /**
     * Gracefully shuts down the executor, awaiting in-flight virtual threads.
     */
    void shutdown();
}
