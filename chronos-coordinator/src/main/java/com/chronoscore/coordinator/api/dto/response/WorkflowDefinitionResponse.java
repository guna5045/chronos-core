package com.chronoscore.coordinator.api.dto.response;

import com.chronoscore.common.domain.workflow.WorkflowDefinition;

import java.time.Instant;
import java.util.UUID;

/**
 * REST response representing a workflow definition namespace.
 */
public record WorkflowDefinitionResponse(
        UUID workflowId,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt
) {

    public static WorkflowDefinitionResponse from(WorkflowDefinition def) {
        if (def == null) {
            return null;
        }
        return new WorkflowDefinitionResponse(
                def.getWorkflowId(),
                def.getName(),
                def.getDescription(),
                def.getCreatedAt(),
                def.getUpdatedAt()
        );
    }
}
