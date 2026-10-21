package com.chronoscore.coordinator.api.dto.response;

import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.domain.workflow.WorkflowVersion;
import com.chronoscore.common.enums.WorkflowVersionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST response representing an immutable workflow version snapshot.
 */
public record WorkflowVersionResponse(
        UUID versionId,
        UUID workflowId,
        int version,
        WorkflowVersionStatus status,
        List<WorkflowNodeDefinition> nodes,
        List<WorkflowEdge> edges,
        Instant createdAt
) {

    public static WorkflowVersionResponse from(WorkflowVersion v) {
        if (v == null) {
            return null;
        }
        return new WorkflowVersionResponse(
                v.getVersionId(),
                v.getWorkflowId(),
                v.getVersion(),
                v.getStatus(),
                v.getNodes() != null ? v.getNodes() : List.of(),
                v.getEdges() != null ? v.getEdges() : List.of(),
                v.getCreatedAt()
        );
    }
}
