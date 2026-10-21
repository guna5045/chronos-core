package com.chronoscore.coordinator.ai.adapter;

import com.chronoscore.ai.ports.AiTaskOperationsPort;
import com.chronoscore.common.domain.Task;
import com.chronoscore.common.dto.TaskSummaryDTO;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import com.chronoscore.coordinator.recovery.dlq.DlqService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing AiTaskOperationsPort by delegating to Chronos Core task persistence and recovery.
 */
@Component
public class TaskOperationsAdapter implements AiTaskOperationsPort {

    private final TaskRepository taskRepository;
    private final TransactionalTaskService taskService;
    private final DlqService dlqService;

    public TaskOperationsAdapter(TaskRepository taskRepository,
                                 TransactionalTaskService taskService,
                                 DlqService dlqService) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.taskService = Objects.requireNonNull(taskService, "taskService must not be null");
        this.dlqService = Objects.requireNonNull(dlqService, "dlqService must not be null");
    }

    @Override
    public Optional<TaskSummaryDTO> getTask(UUID taskId) {
        return taskRepository.findById(taskId).map(this::toSummary);
    }

    @Override
    public List<TaskSummaryDTO> searchTasks(String tenantId, String status, String taskType, int limit) {
        com.chronoscore.common.enums.TaskStatus taskStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                taskStatus = com.chronoscore.common.enums.TaskStatus.valueOf(status.trim().toUpperCase());
            } catch (Exception ignored) {}
        }
        int safeLimit = Math.min(50, Math.max(1, limit));
        List<Task> tasks = taskRepository.findTasksWithFilter(tenantId, taskStatus, taskType, null, safeLimit, 0);
        return tasks.stream().map(this::toSummary).toList();
    }

    @Override
    public void retryTask(UUID taskId, String principal) {
        dlqService.replayTask(taskId, "AI Action Approval by: " + principal);
    }

    @Override
    public void cancelTask(UUID taskId, String principal, String reason) {
        taskService.cancelTask(taskId, "AI Action Approval (" + principal + "): " + (reason != null ? reason : "Cancelled"));
    }

    private TaskSummaryDTO toSummary(Task t) {
        return new TaskSummaryDTO(
                t.getTaskId(),
                t.getTaskType(),
                t.getPriority(),
                t.getStatus(),
                t.getTenantId(),
                t.getCreatedAt(),
                t.getCurrentAttempt(),
                t.getMaxAttempts(),
                t.getVersion()
        );
    }
}
