package com.chronoscore.coordinator.workflow.repository;

import com.chronoscore.common.domain.workflow.WorkflowExecution;
import com.chronoscore.common.domain.workflow.WorkflowExecutionNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for workflow execution instances and execution node states.
 */
public interface WorkflowExecutionRepository {

    WorkflowExecution saveExecution(WorkflowExecution execution);

    WorkflowExecution updateExecution(WorkflowExecution execution);

    Optional<WorkflowExecution> findExecutionById(UUID executionId);

    WorkflowExecutionNode saveExecutionNode(WorkflowExecutionNode node);

    void saveExecutionNodes(List<WorkflowExecutionNode> nodes);

    WorkflowExecutionNode updateExecutionNode(WorkflowExecutionNode node);

    Optional<WorkflowExecutionNode> findExecutionNode(UUID executionId, String nodeId);

    Optional<WorkflowExecutionNode> findExecutionNodeByTaskId(UUID taskId);

    List<WorkflowExecutionNode> findExecutionNodes(UUID executionId);

    /**
     * Checks if all predecessor dependencies of a given node have reached SUCCEEDED status.
     *
     * @param executionId workflow execution ID.
     * @param versionId   workflow version ID.
     * @param nodeId      node ID being evaluated.
     * @return true if every predecessor node is SUCCEEDED (or node has 0 predecessors), false otherwise.
     */
    boolean areAllPredecessorsSucceeded(UUID executionId, UUID versionId, String nodeId);

    /**
     * Returns count of nodes in the execution that have not reached SUCCEEDED status.
     *
     * @param executionId workflow execution ID.
     * @return count of non-succeeded nodes.
     */
    int countNonSucceededNodes(UUID executionId);

    /**
     * Atomically claims a node for activation by transitioning its status from PENDING to READY.
     * Guaranteed to succeed for at most one thread in concurrent activation races.
     *
     * @param executionId workflow execution ID.
     * @param nodeId      node ID to claim.
     * @param startedAt   activation timestamp.
     * @return true if successfully claimed from PENDING to READY, false otherwise.
     */
    boolean claimNodeForActivation(UUID executionId, String nodeId, java.time.Instant startedAt);

    /**
     * Links a generated Chronos task ID to an activated workflow execution node.
     *
     * @param executionId workflow execution ID.
     * @param nodeId      node ID.
     * @param taskId      Chronos task UUID.
     */
    void linkTaskToNode(UUID executionId, String nodeId, UUID taskId);
}
