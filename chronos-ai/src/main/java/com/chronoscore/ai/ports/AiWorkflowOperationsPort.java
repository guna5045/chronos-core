package com.chronoscore.ai.ports;

import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operational port for workflow queries, execution control, and proposal materialization.
 */
public interface AiWorkflowOperationsPort {

    record WorkflowCreatedResult(
            UUID workflowId,
            String name,
            int version,
            String status
    ) {}

    record WorkflowExecutionDetail(
            UUID executionId,
            UUID workflowId,
            long version,
            String status,
            Instant startedAt,
            Instant completedAt,
            Map<String, String> nodeStatuses
    ) {}

    WorkflowCreatedResult createWorkflow(String name, String description,
                                         List<WorkflowNodeDefinition> nodes,
                                         List<WorkflowEdge> edges);

    Optional<WorkflowExecutionDetail> getExecution(UUID executionId);

    void cancelExecution(UUID executionId, String principal);
}
