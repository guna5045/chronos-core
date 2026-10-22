package com.chronoscore.coordinator.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Production Micrometer-backed implementation of {@link SchedulerMetricsRecorder}.
 * Emits low-cardinality Prometheus-compatible scheduler metrics.
 */
@Component
@Primary
public class MicrometerSchedulerMetricsRecorder implements SchedulerMetricsRecorder {

    private final MeterRegistry registry;
    private final Counter pollCounter;
    private final Counter tasksSelectedCounter;
    private final Counter noWorkerCounter;
    private final Timer dispatchTimer;

    public MicrometerSchedulerMetricsRecorder(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry must not be null");

        this.pollCounter = Counter.builder("chronos.scheduler.polls.total")
                .description("Total number of scheduler polling iterations")
                .register(registry);

        this.tasksSelectedCounter = Counter.builder("chronos.scheduler.tasks.selected.total")
                .description("Total candidate tasks fetched from persistence")
                .register(registry);

        this.noWorkerCounter = Counter.builder("chronos.scheduler.no.worker.total")
                .description("Total scheduling cycles where no eligible worker was available on the ring")
                .register(registry);

        this.dispatchTimer = Timer.builder("chronos.tasks.dispatch")
                .description("Duration of unary gRPC dispatch calls to workers")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @Override
    public void recordPoll() {
        pollCounter.increment();
    }

    @Override
    public void recordTasksSelected(int count) {
        if (count > 0) {
            tasksSelectedCounter.increment(count);
        }
    }

    @Override
    public void recordTaskDispatched(String taskType, String workerId) {
        String safeTaskType = (taskType != null && !taskType.isBlank()) ? taskType : "unknown";
        registry.counter("chronos.scheduler.tasks.dispatched.total", "task_type", safeTaskType).increment();
    }

    @Override
    public void recordTaskDispatchRejected(String reason) {
        String safeReason = (reason != null && !reason.isBlank()) ? reason : "unknown";
        registry.counter("chronos.scheduler.dispatch.rejected.total", "reason", safeReason).increment();
    }

    @Override
    public void recordDispatchLatency(Duration latency) {
        if (latency != null) {
            dispatchTimer.record(latency.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void recordNoEligibleWorker() {
        noWorkerCounter.increment();
    }

    @Override
    public void recordWorkerCapacityExhausted(String workerId) {
        registry.counter("chronos.scheduler.capacity.exhausted.total").increment();
    }
}
