package com.chronoscore.coordinator.workflow.repository;

import com.chronoscore.common.domain.workflow.WorkflowDefinition;
import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.domain.workflow.WorkflowVersion;
import com.chronoscore.common.enums.WorkflowVersionStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for Workflow definitions, version snapshots, nodes, and edges.
 */
public interface WorkflowRepository {

    WorkflowDefinition saveWorkflow(WorkflowDefinition workflow);

    Optional<WorkflowDefinition> findWorkflowById(UUID workflowId);

    Optional<WorkflowDefinition> findWorkflowByName(String name);

    WorkflowVersion saveVersion(WorkflowVersion version);

    Optional<WorkflowVersion> findVersionById(UUID versionId);

    Optional<WorkflowVersion> findVersionByNumber(UUID workflowId, int version);

    Optional<WorkflowVersion> findLatestPublishedVersion(UUID workflowId);

    void updateVersionStatus(UUID versionId, WorkflowVersionStatus status);

    List<WorkflowNodeDefinition> findNodesByVersion(UUID versionId);

    List<WorkflowEdge> findEdgesByVersion(UUID versionId);

    default List<WorkflowDefinition> findAllWorkflows(int limit, int offset) {
        return java.util.Collections.emptyList();
    }

    default int countWorkflows() {
        return 0;
    }

    default List<WorkflowVersion> findVersionsByWorkflowId(UUID workflowId) {
        return java.util.Collections.emptyList();
    }
}
