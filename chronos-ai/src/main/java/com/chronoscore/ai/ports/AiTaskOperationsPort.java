package com.chronoscore.ai.ports;

import com.chronoscore.common.dto.TaskSummaryDTO;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Operational port for task queries and mutations invoked via AI approval.
 */
public interface AiTaskOperationsPort {

    Optional<TaskSummaryDTO> getTask(UUID taskId);

    List<TaskSummaryDTO> searchTasks(String tenantId, String status, String taskType, int limit);

    void retryTask(UUID taskId, String principal);

    void cancelTask(UUID taskId, String principal, String reason);
}
