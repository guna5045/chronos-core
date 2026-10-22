package com.chronoscore.coordinator.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production Micrometer metrics service providing application-wide instrumentation
 * for tasks, workers, workflows, coordination, DLQ, and AI operations.
 */
@Service
public class ChronosMetricsService {

    private final MeterRegistry registry;

    // Coordination state gauges
    private final AtomicInteger leadershipGauge = new AtomicInteger(0);
    private final AtomicLong leaderEpochGauge = new AtomicLong(0L);

    // Fleet state gauges
    private final AtomicInteger activeWorkersGauge = new AtomicInteger(0);
    private final AtomicInteger suspectedWorkersGauge = new AtomicInteger(0);
    private final AtomicInteger totalWorkersGauge = new AtomicInteger(0);

    public ChronosMetricsService(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry must not be null");

        // Register custom Gauges
        registry.gauge("chronos.coordinator.leadership.state", leadershipGauge, AtomicInteger::get);
        registry.gauge("chronos.coordinator.fencing.epoch", leaderEpochGauge, AtomicLong::get);
        registry.gauge("chronos.workers.active", activeWorkersGauge, AtomicInteger::get);
        registry.gauge("chronos.workers.suspected", suspectedWorkersGauge, AtomicInteger::get);
        registry.gauge("chronos.workers.total", totalWorkersGauge, AtomicInteger::get);
    }

    // --- Task Lifecycle Metrics ---

    public void recordTaskSubmitted(String taskType) {
        String safeType = sanitize(taskType);
        registry.counter("chronos.tasks.submitted.total", "task_type", safeType).increment();
    }

    public void recordTaskCompleted(String taskType, String status, Duration totalDuration) {
        String safeType = sanitize(taskType);
        String safeStatus = sanitize(status);
        registry.counter("chronos.tasks.completed.total", "task_type", safeType, "status", safeStatus).increment();
        if (totalDuration != null) {
            Timer.builder("chronos.tasks.total")
                    .tag("task_type", safeType)
                    .tag("status", safeStatus)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry)
                    .record(totalDuration.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    public void recordTaskExecution(String taskType, Duration duration) {
        if (duration != null) {
            Timer.builder("chronos.tasks.execution")
                    .tag("task_type", sanitize(taskType))
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry)
                    .record(duration.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    public void recordTaskQueueWait(String taskType, Duration duration) {
        if (duration != null) {
            Timer.builder("chronos.tasks.queue.wait")
                    .tag("task_type", sanitize(taskType))
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry)
                    .record(duration.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    public void recordTaskRetried(String taskType) {
        registry.counter("chronos.tasks.retried.total", "task_type", sanitize(taskType)).increment();
    }

    public void recordTaskDlq(String taskType) {
        registry.counter("chronos.tasks.dlq.total", "task_type", sanitize(taskType)).increment();
    }

    // --- Coordination Metrics ---

    public void updateLeadershipState(boolean isLeader, long epoch) {
        leadershipGauge.set(isLeader ? 1 : 0);
        leaderEpochGauge.set(epoch);
    }

    public void recordLeadershipAcquired() {
        registry.counter("chronos.coordinator.leadership.acquisitions.total").increment();
    }

    public void recordLeadershipLost() {
        registry.counter("chronos.coordinator.leadership.losses.total").increment();
    }

    public void recordLeadershipRenewalFailed() {
        registry.counter("chronos.coordinator.leadership.renewals.failed.total").increment();
    }

    public void recordLockOperation(boolean success) {
        registry.counter("chronos.lock.acquisitions.total", "outcome", success ? "success" : "failure").increment();
    }

    public void recordLockReleased() {
        registry.counter("chronos.lock.releases.total").increment();
    }

    // --- Worker Fleet Metrics ---

    public void updateFleetCounts(int active, int suspected, int total) {
        activeWorkersGauge.set(active);
        suspectedWorkersGauge.set(suspected);
        totalWorkersGauge.set(total);
    }

    public void recordWorkerEvicted() {
        registry.counter("chronos.workers.evicted.total").increment();
    }

    public void recordHeartbeatMissed() {
        registry.counter("chronos.workers.heartbeats.missed.total").increment();
    }

    // --- Workflow Metrics ---

    public void recordWorkflowCreated() {
        registry.counter("chronos.workflows.created.total").increment();
    }

    public void recordWorkflowExecutionStarted() {
        registry.counter("chronos.workflows.executions.started.total").increment();
    }

    public void recordWorkflowExecutionCompleted(String status, Duration duration) {
        String safeStatus = sanitize(status);
        registry.counter("chronos.workflows.executions.completed.total", "status", safeStatus).increment();
        if (duration != null) {
            Timer.builder("chronos.workflows.execution.duration")
                    .tag("status", safeStatus)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry)
                    .record(duration.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    public void recordWorkflowNodeActivated() {
        registry.counter("chronos.workflows.nodes.activated.total").increment();
    }

    // --- Multi-tenant Backpressure Metrics ---

    public void recordTenantRejected(String reason) {
        registry.counter("chronos.tenant.admission.rejected.total", "reason", sanitize(reason)).increment();
    }

    // --- DLQ Metrics ---

    public void recordDlqEntry() {
        registry.counter("chronos.dlq.entries.total").increment();
    }

    public void recordDlqReplay(boolean success) {
        registry.counter("chronos.dlq.replays.total", "outcome", success ? "success" : "failure").increment();
    }

    public void recordDlqArchive() {
        registry.counter("chronos.dlq.archives.total").increment();
    }

    // --- AI Subsystem Metrics ---

    public void recordAiRequest(String type) {
        registry.counter("chronos.ai.requests.total", "type", sanitize(type)).increment();
    }

    public void recordAiSuccess(String type, Duration duration) {
        String safeType = sanitize(type);
        registry.counter("chronos.ai.success.total", "type", safeType).increment();
        if (duration != null) {
            Timer.builder("chronos.ai.duration")
                    .tag("type", safeType)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry)
                    .record(duration.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    public void recordAiFailure(String type, String reason) {
        registry.counter("chronos.ai.failures.total", "type", sanitize(type), "reason", sanitize(reason)).increment();
    }

    public void recordAiProposalGenerated() {
        registry.counter("chronos.ai.proposals.generated.total").increment();
    }

    public void recordAiProposalApproved() {
        registry.counter("chronos.ai.proposals.approved.total").increment();
    }

    public void recordAiProposalRejected() {
        registry.counter("chronos.ai.proposals.rejected.total").increment();
    }

    public void recordAiActionGenerated() {
        registry.counter("chronos.ai.actions.generated.total").increment();
    }

    public void recordAiActionApproved() {
        registry.counter("chronos.ai.actions.approved.total").increment();
    }

    public void recordAiActionRejected() {
        registry.counter("chronos.ai.actions.rejected.total").increment();
    }

    private static String sanitize(String value) {
        return (value != null && !value.isBlank()) ? value.trim().toLowerCase() : "unknown";
    }
}
