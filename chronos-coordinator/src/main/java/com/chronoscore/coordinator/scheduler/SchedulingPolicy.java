package com.chronoscore.coordinator.scheduler;

import com.chronoscore.common.domain.Task;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
     * Selects candidate tasks ready for admission and dispatch as of the specified timestamp.
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

        // 2. Fetch standard priority-ordered schedulable tasks
        int remainingQuota = Math.max(1, batchSize - starvedTasks.size());
        List<Task> priorityTasks = taskRepository.findSchedulableTasks(effectiveAsOf, remainingQuota);

        // 3. Merge: starved tasks are placed ahead to guarantee forward progress, followed by priority tasks
        Map<UUID, Task> merged = new LinkedHashMap<>(batchSize);
        for (Task task : starvedTasks) {
            merged.put(task.getTaskId(), task);
        }
        for (Task task : priorityTasks) {
            merged.putIfAbsent(task.getTaskId(), task);
        }

        return new ArrayList<>(merged.values());
    }
}
