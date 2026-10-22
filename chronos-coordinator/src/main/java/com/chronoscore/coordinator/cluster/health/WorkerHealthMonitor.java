package com.chronoscore.coordinator.cluster.health;

import com.chronoscore.coordinator.cluster.config.ClusterProperties;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background monitor evaluating worker node liveness and transitions based on heartbeat timestamps.
 * <p>
 * Transitions:
 * <ul>
 *   <li>ACTIVE -&gt; SUSPECTED: after missing heartbeats exceeding {@code heartbeatTimeout} (default 3s).</li>
 *   <li>SUSPECTED -&gt; REMOVED: after continued absence exceeding {@code evictionTimeout} (default 6s).</li>
 * </ul>
 */
@Component
public class WorkerHealthMonitor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerHealthMonitor.class);

    private final WorkerRegistry workerRegistry;
    private final ClusterProperties clusterProperties;
    private final com.chronoscore.coordinator.recovery.TaskRecoveryService taskRecoveryService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    private final com.chronoscore.coordinator.observability.ChronosMetricsService metricsService;

    public WorkerHealthMonitor(WorkerRegistry workerRegistry, ClusterProperties clusterProperties) {
        this(workerRegistry, clusterProperties, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WorkerHealthMonitor(WorkerRegistry workerRegistry,
                               ClusterProperties clusterProperties,
                               @org.springframework.beans.factory.annotation.Autowired(required = false)
                               com.chronoscore.coordinator.recovery.TaskRecoveryService taskRecoveryService,
                               @org.springframework.beans.factory.annotation.Autowired(required = false)
                               com.chronoscore.coordinator.observability.ChronosMetricsService metricsService) {
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry must not be null");
        this.clusterProperties = Objects.requireNonNull(clusterProperties, "clusterProperties must not be null");
        this.taskRecoveryService = taskRecoveryService;
        this.metricsService = metricsService;
    }

    /**
     * Executes an immediate synchronous health check tick across all registered workers.
     * Accessible for testing and scheduled runs.
     */
    public void checkHealthNow() {
        Instant now = Instant.now();
        Duration heartbeatTimeout = clusterProperties.getCluster().getHeartbeatTimeout();
        Duration evictionTimeout = clusterProperties.getCluster().getEvictionTimeout();

        int active = 0;
        int suspected = 0;
        var all = workerRegistry.getAllWorkers();
        for (WorkerNode worker : all) {
            Duration elapsed = Duration.between(worker.lastHeartbeatAt(), now);

            if (worker.state() == WorkerState.ACTIVE && elapsed.compareTo(heartbeatTimeout) > 0) {
                log.warn("Worker '{}' missed heartbeats (elapsed: {}ms > timeout: {}ms). Transitioning to SUSPECTED.",
                        worker.workerId(), elapsed.toMillis(), heartbeatTimeout.toMillis());
                workerRegistry.updateState(worker.workerId(), worker.sessionId(), WorkerState.SUSPECTED);
                if (metricsService != null) {
                    metricsService.recordHeartbeatMissed();
                }
                suspected++;
            } else if (worker.state() == WorkerState.SUSPECTED && elapsed.compareTo(evictionTimeout) > 0) {
                log.error("Worker '{}' exceeded eviction timeout (elapsed: {}ms > eviction: {}ms). Transitioning to REMOVED.",
                        worker.workerId(), elapsed.toMillis(), evictionTimeout.toMillis());
                workerRegistry.updateState(worker.workerId(), worker.sessionId(), WorkerState.REMOVED);
                if (metricsService != null) {
                    metricsService.recordWorkerEvicted();
                }
                if (taskRecoveryService != null) {
                    try {
                        taskRecoveryService.recoverWorkerFailure(worker.workerId());
                    } catch (Exception ex) {
                        log.error("Failed to recover tasks for evicted worker '{}': {}", worker.workerId(), ex.getMessage(), ex);
                    }
                }
            } else if (worker.state() == WorkerState.ACTIVE) {
                active++;
            } else if (worker.state() == WorkerState.SUSPECTED) {
                suspected++;
            }
        }

        if (metricsService != null) {
            metricsService.updateFleetCounts(active, suspected, all.size());
        }
    }

    @Override
    public void start() {
        if (!clusterProperties.getCluster().isEnabled()) {
            log.info("WorkerHealthMonitor disabled by configuration");
            return;
        }

        if (running.compareAndSet(false, true)) {
            log.info("Starting WorkerHealthMonitor (interval: {}, heartbeatTimeout: {}, evictionTimeout: {})",
                    clusterProperties.getCluster().getHealthCheckInterval(),
                    clusterProperties.getCluster().getHeartbeatTimeout(),
                    clusterProperties.getCluster().getEvictionTimeout());

            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chronos-worker-health-monitor");
                t.setDaemon(true);
                return t;
            });

            long intervalMs = Math.max(100, clusterProperties.getCluster().getHealthCheckInterval().toMillis());
            this.scheduler.scheduleWithFixedDelay(
                    () -> {
                        try {
                            checkHealthNow();
                        } catch (Exception ex) {
                            log.error("Unexpected error during worker health check cycle: {}", ex.getMessage(), ex);
                        }
                    },
                    intervalMs,
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping WorkerHealthMonitor");
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 50;
    }
}
