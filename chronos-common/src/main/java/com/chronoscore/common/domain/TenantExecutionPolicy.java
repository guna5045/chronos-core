package com.chronoscore.common.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable execution policy governing resource allocation, quotas, and limits for a specific tenant.
 * <p>
 * Configurable boundaries:
 * <ul>
 *   <li><b>maxConcurrentTasks</b>: Maximum number of active tasks simultaneously executing (DISPATCHED or RUNNING).</li>
 *   <li><b>maxQueuedTasks</b>: Maximum number of unexecuted tasks allowed in the QUEUED state before rejection.</li>
 *   <li><b>tasksPerSecond</b>: Steady-state token bucket refill rate for admission rate limiting.</li>
 *   <li><b>burstCapacity</b>: Maximum burst capacity in the token bucket rate limiter.</li>
 *   <li><b>maxWorkflowExecutions</b>: Maximum concurrent workflow execution instances permitted for this tenant.</li>
 * </ul>
 */
public final class TenantExecutionPolicy {

    public static final int DEFAULT_MAX_CONCURRENT_TASKS = 100;
    public static final int DEFAULT_MAX_QUEUED_TASKS = 10000;
    public static final double DEFAULT_TASKS_PER_SECOND = 100.0;
    public static final int DEFAULT_BURST_CAPACITY = 200;
    public static final int DEFAULT_MAX_WORKFLOW_EXECUTIONS = 50;

    private final String tenantId;
    private final int maxConcurrentTasks;
    private final int maxQueuedTasks;
    private final double tasksPerSecond;
    private final int burstCapacity;
    private final int maxWorkflowExecutions;
    private final Instant createdAt;
    private final Instant updatedAt;

    private TenantExecutionPolicy(Builder builder) {
        this.tenantId = Objects.requireNonNull(builder.tenantId, "tenantId must not be null");
        if (builder.maxConcurrentTasks <= 0) {
            throw new IllegalArgumentException("maxConcurrentTasks must be > 0: " + builder.maxConcurrentTasks);
        }
        if (builder.maxQueuedTasks <= 0) {
            throw new IllegalArgumentException("maxQueuedTasks must be > 0: " + builder.maxQueuedTasks);
        }
        if (builder.tasksPerSecond <= 0) {
            throw new IllegalArgumentException("tasksPerSecond must be > 0: " + builder.tasksPerSecond);
        }
        if (builder.burstCapacity < 1) {
            throw new IllegalArgumentException("burstCapacity must be >= 1: " + builder.burstCapacity);
        }
        if (builder.maxWorkflowExecutions <= 0) {
            throw new IllegalArgumentException("maxWorkflowExecutions must be > 0: " + builder.maxWorkflowExecutions);
        }

        this.maxConcurrentTasks = builder.maxConcurrentTasks;
        this.maxQueuedTasks = builder.maxQueuedTasks;
        this.tasksPerSecond = builder.tasksPerSecond;
        this.burstCapacity = builder.burstCapacity;
        this.maxWorkflowExecutions = builder.maxWorkflowExecutions;
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.updatedAt = builder.updatedAt != null ? builder.updatedAt : this.createdAt;
    }

    public static TenantExecutionPolicy defaultPolicy(String tenantId) {
        return builder()
                .tenantId(tenantId)
                .maxConcurrentTasks(DEFAULT_MAX_CONCURRENT_TASKS)
                .maxQueuedTasks(DEFAULT_MAX_QUEUED_TASKS)
                .tasksPerSecond(DEFAULT_TASKS_PER_SECOND)
                .burstCapacity(DEFAULT_BURST_CAPACITY)
                .maxWorkflowExecutions(DEFAULT_MAX_WORKFLOW_EXECUTIONS)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTenantId() {
        return tenantId;
    }

    public int getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    public int getMaxQueuedTasks() {
        return maxQueuedTasks;
    }

    public double getTasksPerSecond() {
        return tasksPerSecond;
    }

    public int getBurstCapacity() {
        return burstCapacity;
    }

    public int getMaxWorkflowExecutions() {
        return maxWorkflowExecutions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Builder toBuilder() {
        return new Builder()
                .tenantId(this.tenantId)
                .maxConcurrentTasks(this.maxConcurrentTasks)
                .maxQueuedTasks(this.maxQueuedTasks)
                .tasksPerSecond(this.tasksPerSecond)
                .burstCapacity(this.burstCapacity)
                .maxWorkflowExecutions(this.maxWorkflowExecutions)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TenantExecutionPolicy that = (TenantExecutionPolicy) o;
        return Objects.equals(tenantId, that.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId);
    }

    @Override
    public String toString() {
        return "TenantExecutionPolicy{" +
                "tenantId='" + tenantId + '\'' +
                ", maxConcurrentTasks=" + maxConcurrentTasks +
                ", maxQueuedTasks=" + maxQueuedTasks +
                ", tasksPerSecond=" + tasksPerSecond +
                ", burstCapacity=" + burstCapacity +
                ", maxWorkflowExecutions=" + maxWorkflowExecutions +
                '}';
    }

    public static final class Builder {
        private String tenantId;
        private int maxConcurrentTasks = DEFAULT_MAX_CONCURRENT_TASKS;
        private int maxQueuedTasks = DEFAULT_MAX_QUEUED_TASKS;
        private double tasksPerSecond = DEFAULT_TASKS_PER_SECOND;
        private int burstCapacity = DEFAULT_BURST_CAPACITY;
        private int maxWorkflowExecutions = DEFAULT_MAX_WORKFLOW_EXECUTIONS;
        private Instant createdAt;
        private Instant updatedAt;

        private Builder() {}

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder maxConcurrentTasks(int maxConcurrentTasks) {
            this.maxConcurrentTasks = maxConcurrentTasks;
            return this;
        }

        public Builder maxQueuedTasks(int maxQueuedTasks) {
            this.maxQueuedTasks = maxQueuedTasks;
            return this;
        }

        public Builder tasksPerSecond(double tasksPerSecond) {
            this.tasksPerSecond = tasksPerSecond;
            return this;
        }

        public Builder burstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
            return this;
        }

        public Builder maxWorkflowExecutions(int maxWorkflowExecutions) {
            this.maxWorkflowExecutions = maxWorkflowExecutions;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public TenantExecutionPolicy build() {
            return new TenantExecutionPolicy(this);
        }
    }
}
