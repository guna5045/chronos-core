package com.chronoscore.coordinator.api.dto.response;

import com.chronoscore.common.domain.workflow.WorkflowExecutionNode;
import com.chronoscore.common.enums.WorkflowNodeStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * REST response representing a node execution record within a workflow execution.
 */
public record WorkflowNodeResponse(
        String nodeId,
        UUID taskId,
        WorkflowNodeStatus status,
        Instant startedAt,
        Instant completedAt,
        String errorCode,
        String errorMessage
) {

    public static WorkflowNodeResponse from(WorkflowExecutionNode node) {
        if (node == null) {
            return null;
        }
        return new WorkflowNodeResponse(
                node.getNodeId(),
                node.getTaskId(),
                node.getStatus(),
                node.getStartedAt(),
                node.getCompletedAt(),
                node.getErrorCode(),
                node.getErrorMessage()
        );
    }
}
