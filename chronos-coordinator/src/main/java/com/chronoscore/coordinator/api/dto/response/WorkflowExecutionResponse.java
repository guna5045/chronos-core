package com.chronoscore.coordinator.api.dto.response;

import com.chronoscore.common.domain.workflow.WorkflowExecution;
import com.chronoscore.common.enums.WorkflowStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * REST response representing a workflow execution instance.
 */
public record WorkflowExecutionResponse(
        UUID executionId,
        UUID workflowId,
        UUID versionId,
        String tenantId,
        WorkflowStatus status,
        Instant startedAt,
        Instant completedAt
) {

    public static WorkflowExecutionResponse from(WorkflowExecution exec) {
        if (exec == null) {
            return null;
        }
        return new WorkflowExecutionResponse(
                exec.getExecutionId(),
                exec.getWorkflowId(),
                exec.getVersionId(),
                exec.getTenantId(),
                exec.getStatus(),
                exec.getStartedAt(),
                exec.getCompletedAt()
        );
    }
}
