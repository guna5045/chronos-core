package com.chronoscore.coordinator.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the distributed task scheduler.
 */
@ConfigurationProperties(prefix = "chronos.scheduler")
public class SchedulerProperties {

    /**
     * Whether the background scheduling loop is enabled on the leader coordinator.
     * Default: true.
     */
    private boolean enabled = true;

    /**
     * Polling interval between scheduler loop executions in milliseconds.
     * Default: 100ms.
     */
    private long pollIntervalMs = 100;

    /**
     * Maximum number of tasks retrieved from PostgreSQL per scheduling batch.
     * Default: 50.
     */
    private int batchSize = 50;

    /**
     * Timeout for unary gRPC dispatch calls to worker nodes.
     * Default: 5 seconds.
     */
    private Duration dispatchTimeout = Duration.ofSeconds(5);

    /**
     * TTL duration for distributed scheduling task locks in Redis.
     * Default: 30 seconds.
     */
    private Duration taskLockTtl = Duration.ofSeconds(30);

    /**
     * Duration after which a queued task is considered starved and given anti-starvation boost.
     * Default: 30 seconds.
     */
    private Duration agingThreshold = Duration.ofSeconds(30);

    /**
     * Percentage ratio of a batch allocated to starved tasks (0.0 to 1.0).
     * Default: 0.2 (20%).
     */
    private double starvationBatchRatio = 0.2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive");
        }
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    public Duration getDispatchTimeout() {
        return dispatchTimeout;
    }

    public void setDispatchTimeout(Duration dispatchTimeout) {
        this.dispatchTimeout = dispatchTimeout != null ? dispatchTimeout : Duration.ofSeconds(5);
    }

    public Duration getTaskLockTtl() {
        return taskLockTtl;
    }

    public void setTaskLockTtl(Duration taskLockTtl) {
        this.taskLockTtl = taskLockTtl != null ? taskLockTtl : Duration.ofSeconds(30);
    }

    public Duration getAgingThreshold() {
        return agingThreshold;
    }

    public void setAgingThreshold(Duration agingThreshold) {
        this.agingThreshold = agingThreshold != null ? agingThreshold : Duration.ofSeconds(30);
    }

    public double getStarvationBatchRatio() {
        return starvationBatchRatio;
    }

    public void setStarvationBatchRatio(double starvationBatchRatio) {
        if (starvationBatchRatio < 0.0 || starvationBatchRatio > 1.0) {
            throw new IllegalArgumentException("starvationBatchRatio must be between 0.0 and 1.0");
        }
        this.starvationBatchRatio = starvationBatchRatio;
    }

    /**
     * Multiplier applied to remaining batch quota to enlarge the candidate pool
     * for tenant fairness interleaving.
     * Default: 1.
     */
    private int candidatePoolMultiplier = 1;

    public int getCandidatePoolMultiplier() {
        return candidatePoolMultiplier;
    }

    public void setCandidatePoolMultiplier(int candidatePoolMultiplier) {
        this.candidatePoolMultiplier = Math.max(1, candidatePoolMultiplier);
    }
}
