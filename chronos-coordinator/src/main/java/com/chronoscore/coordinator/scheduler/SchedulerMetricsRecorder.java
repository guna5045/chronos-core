package com.chronoscore.coordinator.scheduler;

import java.time.Duration;

/**
 * Metric hooks interface for recording scheduler performance, queue depth, and dispatch outcomes.
 * <p>
 * Designed for seamless integration with Micrometer / Prometheus in Phase 12.
 */
public interface SchedulerMetricsRecorder {

    /** Recorded on each polling iteration tick of the scheduler. */
    void recordPoll();

    /** Recorded when candidate tasks are selected from PostgreSQL. */
    void recordTasksSelected(int count);

    /** Recorded when a task is successfully accepted and transitioned to RUNNING on a worker. */
    void recordTaskDispatched(String taskType, String workerId);

    /** Recorded when a worker rejects dispatch or call fails. */
    void recordTaskDispatchRejected(String reason);

    /** Recorded for the duration taken by unary dispatch gRPC calls. */
    void recordDispatchLatency(Duration latency);

    /** Recorded when no eligible or non-saturated worker could be selected on the ring. */
    void recordNoEligibleWorker();

    /** Recorded when a candidate worker was skipped due to capacity saturation. */
    void recordWorkerCapacityExhausted(String workerId);
}
