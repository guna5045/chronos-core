package com.chronoscore.coordinator.api.dto.request;

import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.enums.TaskPriority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Request payload for creating a new DRAFT workflow version with DAG nodes and edges.
 */
public record CreateWorkflowVersionRequest(
        @NotEmpty(message = "nodes list must not be empty")
        @Valid
        List<WorkflowNodeRequest> nodes,

        @Valid
        List<WorkflowEdgeRequest> edges
) {

    public record WorkflowNodeRequest(
            @NotBlank(message = "nodeId must not be blank")
            String nodeId,

            @NotBlank(message = "taskType must not be blank")
            String taskType,

            String payload,
            TaskPriority priority,
            Long timeoutMs,
            Integer maxAttempts
    ) {
        public WorkflowNodeDefinition toDefinition() {
            return WorkflowNodeDefinition.builder()
                    .nodeId(nodeId.trim())
                    .taskType(taskType.trim())
                    .payload(payload != null ? payload : "{}")
                    .priority(priority != null ? priority : TaskPriority.NORMAL)
                    .timeout(timeoutMs != null ? Duration.ofMillis(timeoutMs) : null)
                    .maxAttempts(maxAttempts != null ? maxAttempts : 3)
                    .build();
        }
    }

    public record WorkflowEdgeRequest(
            @NotBlank(message = "fromNodeId must not be blank")
            String fromNodeId,

            @NotBlank(message = "toNodeId must not be blank")
            String toNodeId
    ) {
        public WorkflowEdge toEdge() {
            return WorkflowEdge.of(fromNodeId.trim(), toNodeId.trim());
        }
    }

    public List<WorkflowNodeDefinition> toNodeDefinitions() {
        if (nodes == null) {
            return Collections.emptyList();
        }
        return nodes.stream().map(WorkflowNodeRequest::toDefinition).toList();
    }

    public List<WorkflowEdge> toEdgeDefinitions() {
        if (edges == null) {
            return Collections.emptyList();
        }
        return edges.stream().map(WorkflowEdgeRequest::toEdge).toList();
    }
}
