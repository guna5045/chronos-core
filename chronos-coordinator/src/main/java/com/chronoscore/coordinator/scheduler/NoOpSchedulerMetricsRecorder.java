package com.chronoscore.coordinator.scheduler;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Default no-op implementation of {@link SchedulerMetricsRecorder}.
 */
@Component
public class NoOpSchedulerMetricsRecorder implements SchedulerMetricsRecorder {

    @Override
    public void recordPoll() {
        // No-op
    }

    @Override
    public void recordTasksSelected(int count) {
        // No-op
    }

    @Override
    public void recordTaskDispatched(String taskType, String workerId) {
        // No-op
    }

    @Override
    public void recordTaskDispatchRejected(String reason) {
        // No-op
    }

    @Override
    public void recordDispatchLatency(Duration latency) {
        // No-op
    }

    @Override
    public void recordNoEligibleWorker() {
        // No-op
    }

    @Override
    public void recordWorkerCapacityExhausted(String workerId) {
        // No-op
    }
}
