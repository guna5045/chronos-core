package com.chronoscore.coordinator.scheduler;

/**
 * Summary outcome of a single task scheduler iteration.
 */
public record SchedulingResult(
        boolean active,
        String message,
        int tasksSelected,
        int tasksDispatched,
        int tasksRejected,
        int tasksSkipped,
        int deadlinesExpired
) {

    public static SchedulingResult paused(String reason) {
        return new SchedulingResult(false, reason, 0, 0, 0, 0, 0);
    }

    public static SchedulingResult empty() {
        return new SchedulingResult(true, "No schedulable tasks found in queue", 0, 0, 0, 0, 0);
    }

    public static SchedulingResult success(int selected, int dispatched, int rejected, int skipped, int deadlines) {
        return new SchedulingResult(true, "Batch complete", selected, dispatched, rejected, skipped, deadlines);
    }
}
