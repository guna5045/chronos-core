package com.chronoscore.coordinator.ai.adapter;

import com.chronoscore.ai.ports.AiWorkflowOperationsPort;
import com.chronoscore.common.domain.workflow.WorkflowDefinition;
import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowExecution;
import com.chronoscore.common.domain.workflow.WorkflowExecutionNode;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.domain.workflow.WorkflowVersion;
import com.chronoscore.coordinator.workflow.service.WorkflowExecutionService;
import com.chronoscore.coordinator.workflow.service.WorkflowService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing AiWorkflowOperationsPort by delegating to WorkflowService and WorkflowExecutionService.
 */
@Component
public class WorkflowOperationsAdapter implements AiWorkflowOperationsPort {

    private final WorkflowService workflowService;
    private final WorkflowExecutionService executionService;

    public WorkflowOperationsAdapter(WorkflowService workflowService, WorkflowExecutionService executionService) {
        this.workflowService = Objects.requireNonNull(workflowService, "workflowService must not be null");
        this.executionService = Objects.requireNonNull(executionService, "executionService must not be null");
    }

    @Override
    public WorkflowCreatedResult createWorkflow(String name, String description,
                                                List<WorkflowNodeDefinition> nodes,
                                                List<WorkflowEdge> edges) {
        WorkflowDefinition workflow = workflowService.createWorkflow(name, description);
        WorkflowVersion draft = workflowService.createDraftVersion(workflow.getWorkflowId(), nodes, edges);

        return new WorkflowCreatedResult(
                workflow.getWorkflowId(),
                workflow.getName(),
                draft.getVersion(),
                draft.getStatus().name()
        );
    }

    @Override
    public Optional<WorkflowExecutionDetail> getExecution(UUID executionId) {
        Optional<WorkflowExecution> execOpt = executionService.getExecution(executionId);
        if (execOpt.isEmpty()) {
            return Optional.empty();
        }
        WorkflowExecution exec = execOpt.get();
        List<WorkflowExecutionNode> nodes = executionService.getExecutionNodes(executionId);
        Map<String, String> nodeStatuses = new HashMap<>();
        for (WorkflowExecutionNode n : nodes) {
            nodeStatuses.put(n.getNodeId(), n.getStatus().name());
        }

        return Optional.of(new WorkflowExecutionDetail(
                exec.getExecutionId(),
                exec.getWorkflowId(),
                exec.getVersion(),
                exec.getStatus().name(),
                exec.getStartedAt(),
                exec.getCompletedAt(),
                nodeStatuses
        ));
    }

    @Override
    public void cancelExecution(UUID executionId, String principal) {
        executionService.cancelExecution(executionId, "AI Action Approval by: " + principal);
    }
}
