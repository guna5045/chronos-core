package com.chronoscore.common.enums;

/**
 * Explicit priority representation for Task scheduling.
 * <p>
 * Priority levels are assigned integer weights to enable deterministic comparator ordering
 * within dispatch queues. In Phase 07, the priority scheduler will combine this base priority
 * with aging algorithms to guarantee fairness and prevent starvation.
 */
public enum TaskPriority {

    /**
     * Low priority tasks (batch, housekeeping, background reports).
     */
    LOW(0),

    /**
     * Normal priority (default workload level).
     */
    NORMAL(10),

    /**
     * High priority tasks (time-sensitive jobs).
     */
    HIGH(20),

    /**
     * Critical priority tasks (system health, emergency remediation, SLA-critical).
     */
    CRITICAL(30);

    private final int level;

    TaskPriority(int level) {
        this.level = level;
    }

    /**
     * Returns the integer priority level.
     *
     * @return numeric level (higher is more urgent).
     */
    public int getLevel() {
        return level;
    }

    /**
     * Checks if this priority level is strictly higher than another priority.
     *
     * @param other the other priority to compare with.
     * @return true if this priority is higher.
     */
    public boolean isHigherPriorityThan(TaskPriority other) {
        if (other == null) {
            return true;
        }
        return this.level > other.level;
    }
}
