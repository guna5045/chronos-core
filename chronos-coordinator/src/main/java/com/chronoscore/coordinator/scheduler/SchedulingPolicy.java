package com.chronoscore.coordinator.scheduler;

import com.chronoscore.common.domain.Task;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task selection policy balancing priority-driven ordering with anti-starvation aging guarantees.
 * <p>
 * <b>Selection Rules:</b>
 * <ol>
 *   <li><b>Scheduled-At Gate</b>: Only tasks whose schedule time has arrived ({@code scheduled_at <= asOf}
 *       or {@code scheduled_at IS NULL}) are ever eligible for selection. Future tasks remain in queue.</li>
 *   <li><b>Priority-First Ordering</b>: Tasks are normally ordered by {@code CRITICAL > HIGH > NORMAL > LOW}.</li>
 *   <li><b>Starvation Aging Prevention</b>: Tasks that have remained queued beyond {@code agingThreshold}
 *       are prioritized into an anti-starvation quota (default 20% of the batch) to guarantee that lower-priority
 *       work is never starved indefinitely under continuous high-priority traffic.</li>
 * </ol>
 */
@Component
public class SchedulingPolicy {

    private final TaskRepository taskRepository;
    private final SchedulerProperties schedulerProperties;

    public SchedulingPolicy(TaskRepository taskRepository, SchedulerProperties schedulerProperties) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.schedulerProperties = Objects.requireNonNull(schedulerProperties, "schedulerProperties must not be null");
    }

    /**
     * Selects candidate tasks ready for admission and dispatch as of the specified timestamp,
     * applying anti-starvation aging guarantees combined with tenant-aware round-robin fair queueing.
     *
     * @param asOf current baseline timestamp.
     * @return ordered list of candidate tasks up to the configured batch size.
     */
    public List<Task> selectCandidateTasks(Instant asOf) {
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();
        int batchSize = schedulerProperties.getBatchSize();
        Duration agingThreshold = schedulerProperties.getAgingThreshold();
        double starvationRatio = schedulerProperties.getStarvationBatchRatio();

        int maxStarved = Math.max(1, (int) Math.round(batchSize * starvationRatio));

        // 1. Fetch starved tasks that have been queued longer than the aging threshold
        List<Task> starvedTasks = taskRepository.findStarvedTasks(effectiveAsOf, agingThreshold, maxStarved);
        List<Task> fairStarved = fairInterleave(starvedTasks, maxStarved);

        // 2. Fetch candidate schedulable tasks using candidate pool window
        int remainingQuota = Math.max(0, batchSize - fairStarved.size());
        int candidatePoolSize = remainingQuota * schedulerProperties.getCandidatePoolMultiplier();
        List<Task> priorityCandidates = candidatePoolSize > 0
                ? taskRepository.findSchedulableTasks(effectiveAsOf, candidatePoolSize)
                : Collections.emptyList();
        List<Task> fairPriority = fairInterleave(priorityCandidates, remainingQuota);

        // 3. Merge: starved tasks are placed ahead to guarantee forward progress, followed by tenant-fair priority tasks
        Map<UUID, Task> merged = new LinkedHashMap<>(batchSize);
        for (Task task : fairStarved) {
            merged.put(task.getTaskId(), task);
        }
        for (Task task : fairPriority) {
            if (merged.size() >= batchSize) {
                break;
            }
            merged.putIfAbsent(task.getTaskId(), task);
        }

        return new ArrayList<>(merged.values());
    }

    /**
     * Interleaves tasks across distinct tenants in round-robin fashion while preserving
     * priority order within each individual tenant's allocation.
     *
     * @param tasks    input task list.
     * @param maxCount maximum output elements.
     * @return interleaved task list.
     */
    public List<Task> fairInterleave(List<Task> tasks, int maxCount) {
        if (tasks == null || tasks.isEmpty() || maxCount <= 0) {
            return new ArrayList<>();
        }

        Map<String, java.util.Queue<Task>> perTenant = new LinkedHashMap<>();
        for (Task task : tasks) {
            perTenant.computeIfAbsent(task.getTenantId(), k -> new java.util.ArrayDeque<>()).add(task);
        }

        List<Task> interleaved = new ArrayList<>(Math.min(tasks.size(), maxCount));
        boolean anyAdded = true;
        while (interleaved.size() < maxCount && anyAdded) {
            anyAdded = false;
            for (java.util.Queue<Task> queue : perTenant.values()) {
                if (!queue.isEmpty()) {
                    interleaved.add(queue.poll());
                    anyAdded = true;
                    if (interleaved.size() >= maxCount) {
                        break;
                    }
                }
            }
        }
        return interleaved;
    }
}
