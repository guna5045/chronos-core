package com.chronoscore.coordinator.workflow.service;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowExecution;
import com.chronoscore.common.domain.workflow.WorkflowExecutionNode;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.domain.workflow.WorkflowVersion;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.enums.WorkflowNodeStatus;
import com.chronoscore.common.enums.WorkflowStatus;
import com.chronoscore.common.enums.WorkflowVersionStatus;
import com.chronoscore.common.exceptions.WorkflowExecutionNotFoundException;
import com.chronoscore.common.exceptions.WorkflowNotFoundException;
import com.chronoscore.common.proto.ProtoTaskCancelRequest;
import com.chronoscore.common.statemachine.WorkflowNodeStateMachine;
import com.chronoscore.common.statemachine.WorkflowStateMachine;
import com.chronoscore.common.validation.WorkflowDagValidator;
import com.chronoscore.coordinator.cluster.grpc.WorkerDispatchClient;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import com.chronoscore.coordinator.persistence.model.OutboxEvent;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import com.chronoscore.coordinator.recovery.lease.ExecutionLeaseService;
import com.chronoscore.coordinator.recovery.lease.TaskExecutionLease;
import com.chronoscore.coordinator.workflow.repository.WorkflowAuditRepository;
import com.chronoscore.coordinator.workflow.repository.WorkflowExecutionRepository;
import com.chronoscore.coordinator.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service orchestrating workflow executions, dependency-aware node activation,
 * failure propagation, and cancellation.
 */
@Service
public class WorkflowExecutionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionService.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowAuditRepository auditRepository;
    private final OutboxRepository outboxRepository;
    private final TransactionalTaskService transactionalTaskService;
    private final ExecutionLeaseService leaseService;
    private final WorkerRegistry workerRegistry;
    private final WorkerDispatchClient dispatchClient;

    @Autowired
    public WorkflowExecutionService(WorkflowRepository workflowRepository,
                                    WorkflowExecutionRepository executionRepository,
                                    WorkflowAuditRepository auditRepository,
                                    OutboxRepository outboxRepository,
                                    TransactionalTaskService transactionalTaskService,
                                    @Autowired(required = false) ExecutionLeaseService leaseService,
                                    @Autowired(required = false) WorkerRegistry workerRegistry,
                                    @Autowired(required = false) WorkerDispatchClient dispatchClient) {
        this.workflowRepository = Objects.requireNonNull(workflowRepository, "workflowRepository must not be null");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository must not be null");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.transactionalTaskService = Objects.requireNonNull(transactionalTaskService, "transactionalTaskService must not be null");
        this.leaseService = leaseService;
        this.workerRegistry = workerRegistry;
        this.dispatchClient = dispatchClient;
    }

    /**
     * Starts a new workflow execution for a specified workflow and version.
     *
     * @param workflowId workflow definition ID.
     * @param versionNum optional specific version number (null for latest published).
     * @param tenantId   tenant identifier.
     * @return active WorkflowExecution instance.
     */
    @Transactional
    public WorkflowExecution startExecution(UUID workflowId, Integer versionNum, String tenantId) {
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        WorkflowVersion version;
        if (versionNum != null) {
            version = workflowRepository.findVersionByNumber(workflowId, versionNum)
                    .orElseThrow(() -> new WorkflowNotFoundException(
                            String.format("Workflow [%s] version [%d] not found", workflowId, versionNum)));
        } else {
            version = workflowRepository.findLatestPublishedVersion(workflowId)
                    .orElseThrow(() -> new WorkflowNotFoundException(
                            String.format("No published version found for workflow [%s]", workflowId)));
        }

        if (version.getStatus() != WorkflowVersionStatus.PUBLISHED) {
            throw new IllegalStateException(String.format(
                    "Cannot start execution for workflow [%s] version [%d] in status [%s]",
                    workflowId, version.getVersion(), version.getStatus()));
        }

        UUID executionId = UUID.randomUUID();
        Instant now = Instant.now();

        WorkflowExecution execution = WorkflowExecution.builder()
                .executionId(executionId)
                .workflowId(workflowId)
                .versionId(version.getVersionId())
                .status(WorkflowStatus.RUNNING)
                .tenantId(tenantId)
                .createdAt(now)
                .startedAt(now)
                .version(0L)
                .build();

        executionRepository.saveExecution(execution);

        // Initialize execution node records in PENDING state
        List<WorkflowExecutionNode> executionNodes = new ArrayList<>();
        Map<String, WorkflowNodeDefinition> nodeDefMap = version.getNodes().stream()
                .collect(Collectors.toMap(WorkflowNodeDefinition::getNodeId, n -> n));

        for (WorkflowNodeDefinition nodeDef : version.getNodes()) {
            executionNodes.add(WorkflowExecutionNode.builder()
                    .executionId(executionId)
                    .nodeId(nodeDef.getNodeId())
                    .status(WorkflowNodeStatus.PENDING)
                    .version(0L)
                    .build());
        }
        executionRepository.saveExecutionNodes(executionNodes);

        // Identify root nodes and activate them
        List<String> rootNodeIds = WorkflowDagValidator.getRootNodeIds(version.getNodes(), version.getEdges());
        log.info("Starting workflow execution [{}] (workflow: {}, version: v{}, root nodes: {})",
                executionId, workflowId, version.getVersion(), rootNodeIds);

        for (String rootId : rootNodeIds) {
            WorkflowNodeDefinition nodeDef = nodeDefMap.get(rootId);
            activateNode(executionId, version, nodeDef, tenantId);
        }

        auditRepository.appendAudit(executionId, null, WorkflowStatus.RUNNING.name(),
                "Workflow execution started", "version=" + version.getVersion());

        OutboxEvent outbox = OutboxEvent.builder()
                .aggregateType("WORKFLOW")
                .aggregateId(executionId.toString())
                .eventType("WorkflowExecutionStarted")
                .payload(String.format("{\"executionId\":\"%s\",\"workflowId\":\"%s\",\"version\":%d,\"tenantId\":\"%s\"}",
                        executionId, workflowId, version.getVersion(), tenantId))
                .createdAt(now)
                .build();
        outboxRepository.append(outbox);

        return execution;
    }

    /**
     * Handles task completion events forwarded by the task coordination / recovery layer.
     * Evaluates dependencies, activates eligible downstream nodes, and updates workflow status.
     *
     * @param taskId       task identifier.
     * @param success      true if task completed successfully, false if terminal failure (DLQ).
     * @param errorCode    optional error code on failure.
     * @param errorMessage optional error message on failure.
     * @return true if task was part of a workflow execution, false if standalone task.
     */
    @Transactional
    public boolean onTaskCompleted(UUID taskId, boolean success, String errorCode, String errorMessage) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        Optional<WorkflowExecutionNode> nodeOpt = executionRepository.findExecutionNodeByTaskId(taskId);
        if (nodeOpt.isEmpty()) {
            return false;
        }

        WorkflowExecutionNode node = nodeOpt.get();
        UUID executionId = node.getExecutionId();
        String nodeId = node.getNodeId();
        Instant now = Instant.now();

        Optional<WorkflowExecution> execOpt = executionRepository.findExecutionById(executionId);
        if (execOpt.isEmpty()) {
            log.warn("Workflow execution [{}] not found for task [{}]", executionId, taskId);
            return false;
        }

        WorkflowExecution execution = execOpt.get();
        if (execution.getStatus().isTerminal()) {
            log.debug("Workflow execution [{}] already in terminal status [{}]; ignoring task [{}] completion",
                    executionId, execution.getStatus(), taskId);
            return true;
        }

        if (node.getStatus().isTerminal()) {
            log.debug("Workflow node [{}] in execution [{}] already in terminal status [{}]; idempotent ignore",
                    nodeId, executionId, node.getStatus());
            return true;
        }

        Optional<WorkflowVersion> versionOpt = workflowRepository.findVersionById(execution.getVersionId());
        if (versionOpt.isEmpty()) {
            log.error("Workflow version [{}] not found for execution [{}]", execution.getVersionId(), executionId);
            return false;
        }
        WorkflowVersion version = versionOpt.get();
        Map<String, WorkflowNodeDefinition> nodeDefMap = version.getNodes().stream()
                .collect(Collectors.toMap(WorkflowNodeDefinition::getNodeId, n -> n));

        if (success) {
            // 1. Mark node as SUCCEEDED
            WorkflowNodeStateMachine.transition(executionId, nodeId, node.getStatus(), WorkflowNodeStatus.SUCCEEDED);
            WorkflowExecutionNode updatedNode = node.toBuilder()
                    .status(WorkflowNodeStatus.SUCCEEDED)
                    .completedAt(now)
                    .build();
            executionRepository.updateExecutionNode(updatedNode);
            log.info("Workflow execution [{}] node [{}] succeeded (task: {})", executionId, nodeId, taskId);

            // 2. Evaluate immediate successor nodes
            List<WorkflowEdge> edges = version.getEdges();
            for (WorkflowEdge edge : edges) {
                if (edge.getFromNodeId().equals(nodeId)) {
                    String succNodeId = edge.getToNodeId();
                    boolean allPredecessorsSucceeded = executionRepository.areAllPredecessorsSucceeded(
                            executionId, version.getVersionId(), succNodeId);

                    if (allPredecessorsSucceeded) {
                        Optional<WorkflowExecutionNode> succNodeOpt = executionRepository.findExecutionNode(executionId, succNodeId);
                        if (succNodeOpt.isPresent() && succNodeOpt.get().getStatus() == WorkflowNodeStatus.PENDING) {
                            WorkflowNodeDefinition succDef = nodeDefMap.get(succNodeId);
                            activateNode(executionId, version, succDef, execution.getTenantId());
                        }
                    }
                }
            }

            // 3. Check if all workflow nodes are now SUCCEEDED
            int remainingNonSucceeded = executionRepository.countNonSucceededNodes(executionId);
            if (remainingNonSucceeded == 0) {
                WorkflowStateMachine.transition(executionId, execution.getStatus(), WorkflowStatus.SUCCEEDED);
                WorkflowExecution completedExec = execution.toBuilder()
                        .status(WorkflowStatus.SUCCEEDED)
                        .completedAt(now)
                        .build();
                executionRepository.updateExecution(completedExec);

                auditRepository.appendAudit(executionId, execution.getStatus().name(), WorkflowStatus.SUCCEEDED.name(),
                        "All workflow nodes completed successfully", null);

                OutboxEvent outbox = OutboxEvent.builder()
                        .aggregateType("WORKFLOW")
                        .aggregateId(executionId.toString())
                        .eventType("WorkflowSucceeded")
                        .payload(String.format("{\"executionId\":\"%s\",\"workflowId\":\"%s\"}",
                                executionId, execution.getWorkflowId()))
                        .createdAt(now)
                        .build();
                outboxRepository.append(outbox);

                log.info("Workflow execution [{}] concluded with SUCCEEDED status", executionId);
            }

            return true;
        } else {
            // Node failed terminally (DLQ or permanent non-retryable failure)
            WorkflowNodeStateMachine.transition(executionId, nodeId, node.getStatus(), WorkflowNodeStatus.FAILED);
            WorkflowExecutionNode failedNode = node.toBuilder()
                    .status(WorkflowNodeStatus.FAILED)
                    .completedAt(now)
                    .errorCode(errorCode)
                    .errorMessage(errorMessage)
                    .build();
            executionRepository.updateExecutionNode(failedNode);

            log.warn("Workflow execution [{}] node [{}] failed terminally: {} - {}",
                    executionId, nodeId, errorCode, errorMessage);

            // Failure Propagation: Mark all downstream reachable nodes as SKIPPED
            Set<String> downstreamNodeIds = findDescendantNodeIds(nodeId, version.getEdges());
            for (String descendantId : downstreamNodeIds) {
                Optional<WorkflowExecutionNode> descOpt = executionRepository.findExecutionNode(executionId, descendantId);
                if (descOpt.isPresent() && descOpt.get().getStatus() == WorkflowNodeStatus.PENDING) {
                    WorkflowNodeStateMachine.transition(executionId, descendantId, WorkflowNodeStatus.PENDING, WorkflowNodeStatus.SKIPPED);
                    WorkflowExecutionNode skippedNode = descOpt.get().toBuilder()
                            .status(WorkflowNodeStatus.SKIPPED)
                            .completedAt(now)
                            .errorMessage("Skipped due to upstream failure of node " + nodeId)
                            .build();
                    executionRepository.updateExecutionNode(skippedNode);
                    log.info("Workflow execution [{}] node [{}] marked SKIPPED (upstream failed: {})",
                            executionId, descendantId, nodeId);
                }
            }

            // Transition WorkflowExecution to FAILED
            WorkflowStateMachine.transition(executionId, execution.getStatus(), WorkflowStatus.FAILED);
            WorkflowExecution failedExec = execution.toBuilder()
                    .status(WorkflowStatus.FAILED)
                    .completedAt(now)
                    .build();
            executionRepository.updateExecution(failedExec);

            auditRepository.appendAudit(executionId, execution.getStatus().name(), WorkflowStatus.FAILED.name(),
                    "Workflow failed due to terminal failure of node " + nodeId,
                    "errorCode=" + errorCode + ";errorMessage=" + errorMessage);

            OutboxEvent outbox = OutboxEvent.builder()
                    .aggregateType("WORKFLOW")
                    .aggregateId(executionId.toString())
                    .eventType("WorkflowFailed")
                    .payload(String.format("{\"executionId\":\"%s\",\"workflowId\":\"%s\",\"failedNode\":\"%s\",\"errorCode\":\"%s\"}",
                            executionId, execution.getWorkflowId(), nodeId, errorCode))
                    .createdAt(now)
                    .build();
            outboxRepository.append(outbox);

            return true;
        }
    }

    /**
     * Cancels an active workflow execution.
     *
     * @param executionId workflow execution ID.
     * @param reason      cancellation reason.
     * @return cancelled WorkflowExecution.
     */
    @Transactional
    public WorkflowExecution cancelExecution(UUID executionId, String reason) {
        Objects.requireNonNull(executionId, "executionId must not be null");

        WorkflowExecution execution = executionRepository.findExecutionById(executionId)
                .orElseThrow(() -> new WorkflowExecutionNotFoundException(executionId));

        if (execution.getStatus().isTerminal()) {
            log.debug("Workflow execution [{}] is already terminal [{}]; cannot cancel", executionId, execution.getStatus());
            return execution;
        }

        Instant now = Instant.now();
        WorkflowStateMachine.transition(executionId, execution.getStatus(), WorkflowStatus.CANCELLED);
        WorkflowExecution cancelledExec = execution.toBuilder()
                .status(WorkflowStatus.CANCELLED)
                .completedAt(now)
                .build();
        executionRepository.updateExecution(cancelledExec);

        // Cancel in-progress and pending nodes
        List<WorkflowExecutionNode> nodes = executionRepository.findExecutionNodes(executionId);
        for (WorkflowExecutionNode node : nodes) {
            if (!node.getStatus().isTerminal()) {
                WorkflowNodeStatus prevStatus = node.getStatus();
                WorkflowNodeStateMachine.transition(executionId, node.getNodeId(), prevStatus, WorkflowNodeStatus.CANCELLED);

                WorkflowExecutionNode cancelledNode = node.toBuilder()
                        .status(WorkflowNodeStatus.CANCELLED)
                        .completedAt(now)
                        .errorMessage("Workflow cancelled: " + reason)
                        .build();
                executionRepository.updateExecutionNode(cancelledNode);

                // If underlying Chronos task exists, cancel it
                if (node.getTaskId() != null) {
                    try {
                        transactionalTaskService.transitionTask(
                                node.getTaskId(),
                                TaskStatus.CANCELLED,
                                "Workflow cancelled: " + reason,
                                "executionId=" + executionId
                        );

                        // If worker is actively leasing task, send cooperative cancellation
                        if (leaseService != null && workerRegistry != null && dispatchClient != null) {
                            Optional<TaskExecutionLease> leaseOpt = leaseService.getLease(node.getTaskId());
                            if (leaseOpt.isPresent()) {
                                String workerId = leaseOpt.get().getWorkerId();
                                Optional<WorkerNode> workerOpt = workerRegistry.getWorker(workerId);
                                if (workerOpt.isPresent()) {
                                    ProtoTaskCancelRequest request = ProtoTaskCancelRequest.newBuilder()
                                            .setTaskId(node.getTaskId().toString())
                                            .setReason(reason)
                                            .setRequestedAt(com.google.protobuf.Timestamp.newBuilder()
                                                    .setSeconds(now.getEpochSecond())
                                                    .setNanos(now.getNano())
                                                    .build())
                                            .build();
                                    dispatchClient.cancelTask(workerOpt.get(), request, java.time.Duration.ofSeconds(3));
                                }
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to cancel underlying task [{}] during workflow cancellation: {}",
                                node.getTaskId(), ex.getMessage());
                    }
                }
            }
        }

        auditRepository.appendAudit(executionId, execution.getStatus().name(), WorkflowStatus.CANCELLED.name(),
                "Workflow cancelled: " + reason, null);

        OutboxEvent outbox = OutboxEvent.builder()
                .aggregateType("WORKFLOW")
                .aggregateId(executionId.toString())
                .eventType("WorkflowCancelled")
                .payload(String.format("{\"executionId\":\"%s\",\"reason\":\"%s\"}", executionId, reason))
                .createdAt(now)
                .build();
        outboxRepository.append(outbox);

        log.info("Workflow execution [{}] cancelled: {}", executionId, reason);
        return cancelledExec;
    }

    public Optional<WorkflowExecution> getExecution(UUID executionId) {
        return executionRepository.findExecutionById(executionId);
    }

    public List<WorkflowExecutionNode> getExecutionNodes(UUID executionId) {
        return executionRepository.findExecutionNodes(executionId);
    }

    /**
     * Activates a workflow node by transitioning it to READY and submitting a Chronos Task in QUEUED status.
     * Uses atomic DB-level compare-and-swap claimNodeForActivation to ensure exactly-once task submission
     * under concurrent dependency completion.
     */
    private void activateNode(UUID executionId,
                              WorkflowVersion version,
                              WorkflowNodeDefinition nodeDef,
                              String tenantId) {
        Instant now = Instant.now();
        String nodeId = nodeDef.getNodeId();

        // 1. Atomically claim node for activation: transitions from PENDING -> READY in DB
        boolean claimed = executionRepository.claimNodeForActivation(executionId, nodeId, now);
        if (!claimed) {
            log.debug("Workflow node [{}] in execution [{}] was already claimed or is not PENDING; skipping activation",
                    nodeId, executionId);
            return;
        }

        // 2. Validate state machine transition
        WorkflowNodeStateMachine.transition(executionId, nodeId, WorkflowNodeStatus.PENDING, WorkflowNodeStatus.READY);

        // 3. Create and submit Chronos Task (QUEUED)
        UUID taskId = UUID.randomUUID();
        String idempotencyKey = executionId + ":" + nodeId;

        Task task = Task.builder()
                .taskId(taskId)
                .taskType(nodeDef.getTaskType())
                .payload(nodeDef.getPayload())
                .priority(nodeDef.getPriority())
                .status(TaskStatus.QUEUED)
                .tenantId(tenantId)
                .idempotencyKey(idempotencyKey)
                .timeout(nodeDef.getTimeout())
                .maxAttempts(nodeDef.getMaxAttempts())
                .createdAt(now)
                .build();

        transactionalTaskService.submitTask(task,
                "Workflow execution " + executionId + " node " + nodeId,
                "workflowId=" + version.getWorkflowId() + ";version=" + version.getVersion());

        // 4. Link taskId to execution node
        executionRepository.linkTaskToNode(executionId, nodeId, taskId);

        log.info("Activated workflow execution [{}] node [{}] -> Chronos Task [{}] (QUEUED)",
                executionId, nodeId, taskId);
    }

    /**
     * Finds all downstream descendant node IDs reachable from the source node using BFS.
     */
    private Set<String> findDescendantNodeIds(String sourceNodeId, List<WorkflowEdge> edges) {
        Set<String> descendants = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(sourceNodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (WorkflowEdge edge : edges) {
                if (edge.getFromNodeId().equals(current)) {
                    if (descendants.add(edge.getToNodeId())) {
                        queue.add(edge.getToNodeId());
                    }
                }
            }
        }

        return descendants;
    }
}
