package com.chronoscore.coordinator.workflow.service;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowExecution;
import com.chronoscore.common.domain.workflow.WorkflowExecutionNode;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.domain.workflow.WorkflowVersion;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.enums.WorkflowNodeStatus;
import com.chronoscore.common.enums.WorkflowStatus;
import com.chronoscore.common.enums.WorkflowVersionStatus;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import com.chronoscore.coordinator.workflow.repository.WorkflowAuditRepository;
import com.chronoscore.coordinator.workflow.repository.WorkflowExecutionRepository;
import com.chronoscore.coordinator.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkflowExecutionService Unit Tests")
class WorkflowExecutionServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @Mock
    private WorkflowAuditRepository auditRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private TransactionalTaskService transactionalTaskService;

    private WorkflowExecutionService executionService;

    private UUID workflowId;
    private UUID versionId;
    private WorkflowVersion publishedVersion;
    private Map<String, WorkflowExecutionNode> executionNodeStore;
    private Map<UUID, WorkflowExecutionNode> taskToNodeMap;
    private WorkflowExecution currentExecution;

    @BeforeEach
    void setUp() {
        executionService = new WorkflowExecutionService(
                workflowRepository,
                executionRepository,
                auditRepository,
                outboxRepository,
                transactionalTaskService,
                null, null, null
        );

        workflowId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        executionNodeStore = new HashMap<>();
        taskToNodeMap = new HashMap<>();

        // Wire mock behavior for in-memory simulation
        when(executionRepository.saveExecution(any(WorkflowExecution.class)))
                .thenAnswer(inv -> {
                    currentExecution = inv.getArgument(0);
                    return currentExecution;
                });

        when(executionRepository.updateExecution(any(WorkflowExecution.class)))
                .thenAnswer(inv -> {
                    currentExecution = inv.getArgument(0);
                    return currentExecution;
                });

        when(executionRepository.findExecutionById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(currentExecution));

        doAnswer(inv -> {
            List<WorkflowExecutionNode> nodes = inv.getArgument(0);
            for (WorkflowExecutionNode node : nodes) {
                executionNodeStore.put(node.getNodeId(), node);
            }
            return null;
        }).when(executionRepository).saveExecutionNodes(anyList());

        when(executionRepository.updateExecutionNode(any(WorkflowExecutionNode.class)))
                .thenAnswer(inv -> {
                    WorkflowExecutionNode node = inv.getArgument(0);
                    executionNodeStore.put(node.getNodeId(), node);
                    if (node.getTaskId() != null) {
                        taskToNodeMap.put(node.getTaskId(), node);
                    }
                    return node;
                });

        when(executionRepository.findExecutionNode(any(UUID.class), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(executionNodeStore.get(inv.getArgument(1))));

        when(executionRepository.findExecutionNodeByTaskId(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(taskToNodeMap.get(inv.getArgument(0))));

        when(executionRepository.findExecutionNodes(any(UUID.class)))
                .thenAnswer(inv -> new ArrayList<>(executionNodeStore.values()));

        when(executionRepository.claimNodeForActivation(any(UUID.class), anyString(), any(Instant.class)))
                .thenAnswer(inv -> {
                    String nodeId = inv.getArgument(1);
                    Instant startedAt = inv.getArgument(2);
                    WorkflowExecutionNode node = executionNodeStore.get(nodeId);
                    if (node != null && node.getStatus() == WorkflowNodeStatus.PENDING) {
                        WorkflowExecutionNode readyNode = node.toBuilder()
                                .status(WorkflowNodeStatus.READY)
                                .startedAt(startedAt)
                                .version(node.getVersion() + 1)
                                .build();
                        executionNodeStore.put(nodeId, readyNode);
                        return true;
                    }
                    return false;
                });

        doAnswer(inv -> {
            String nodeId = inv.getArgument(1);
            UUID taskId = inv.getArgument(2);
            WorkflowExecutionNode node = executionNodeStore.get(nodeId);
            if (node != null) {
                WorkflowExecutionNode updated = node.toBuilder().taskId(taskId).build();
                executionNodeStore.put(nodeId, updated);
                taskToNodeMap.put(taskId, updated);
            }
            return null;
        }).when(executionRepository).linkTaskToNode(any(UUID.class), anyString(), any(UUID.class));

        when(transactionalTaskService.submitTask(any(Task.class), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private WorkflowNodeDefinition node(String id) {
        return WorkflowNodeDefinition.builder()
                .nodeId(id)
                .taskType("TASK_" + id)
                .priority(TaskPriority.NORMAL)
                .build();
    }

    private void setupDiamondDag() {
        // A -> B, A -> C, B -> D, C -> D
        List<WorkflowNodeDefinition> nodes = List.of(node("A"), node("B"), node("C"), node("D"));
        List<WorkflowEdge> edges = List.of(
                WorkflowEdge.of("A", "B"),
                WorkflowEdge.of("A", "C"),
                WorkflowEdge.of("B", "D"),
                WorkflowEdge.of("C", "D")
        );

        publishedVersion = WorkflowVersion.builder()
                .versionId(versionId)
                .workflowId(workflowId)
                .version(1)
                .status(WorkflowVersionStatus.PUBLISHED)
                .nodes(nodes)
                .edges(edges)
                .build();

        when(workflowRepository.findLatestPublishedVersion(workflowId)).thenReturn(Optional.of(publishedVersion));
        when(workflowRepository.findVersionById(versionId)).thenReturn(Optional.of(publishedVersion));
    }

    @Test
    @DisplayName("Starting execution activates root nodes and enqueues Chronos tasks")
    void startExecutionActivatesRootNodes() {
        setupDiamondDag();

        WorkflowExecution execution = executionService.startExecution(workflowId, null, "tenant-1");

        assertNotNull(execution);
        assertEquals(WorkflowStatus.RUNNING, execution.getStatus());
        assertEquals(workflowId, execution.getWorkflowId());

        // Node A is root: should be READY and have a Chronos task submitted
        WorkflowExecutionNode nodeA = executionNodeStore.get("A");
        assertNotNull(nodeA);
        assertEquals(WorkflowNodeStatus.READY, nodeA.getStatus());
        assertNotNull(nodeA.getTaskId());

        // Nodes B, C, D should remain PENDING
        assertEquals(WorkflowNodeStatus.PENDING, executionNodeStore.get("B").getStatus());
        assertEquals(WorkflowNodeStatus.PENDING, executionNodeStore.get("C").getStatus());
        assertEquals(WorkflowNodeStatus.PENDING, executionNodeStore.get("D").getStatus());

        // Verify task was submitted with QUEUED status
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(transactionalTaskService).submitTask(taskCaptor.capture(), anyString(), any());
        Task submitted = taskCaptor.getValue();
        assertEquals(TaskStatus.QUEUED, submitted.getStatus());
        assertEquals("TASK_A", submitted.getTaskType());
        assertEquals(execution.getExecutionId() + ":A", submitted.getIdempotencyKey());
    }

    @Test
    @DisplayName("Rejects starting execution on DRAFT or DEPRECATED version")
    void rejectsExecutionOnNonPublishedVersion() {
        WorkflowVersion draftVersion = WorkflowVersion.builder()
                .versionId(versionId)
                .workflowId(workflowId)
                .version(1)
                .status(WorkflowVersionStatus.DRAFT)
                .nodes(List.of(node("A")))
                .build();

        when(workflowRepository.findVersionByNumber(workflowId, 1)).thenReturn(Optional.of(draftVersion));

        assertThrows(IllegalStateException.class,
                () -> executionService.startExecution(workflowId, 1, "tenant-1"));
    }

    @Test
    @DisplayName("Diamond DAG full lifecycle: fan-out to B and C, then fan-in to D, then workflow SUCCEEDED")
    void diamondDagExecutionFullLifecycle() {
        setupDiamondDag();

        // 1. Start execution -> activates A
        WorkflowExecution execution = executionService.startExecution(workflowId, null, "tenant-1");
        WorkflowExecutionNode nodeA = executionNodeStore.get("A");
        UUID taskAId = nodeA.getTaskId();

        // Simulate dependency check responses
        when(executionRepository.areAllPredecessorsSucceeded(eq(execution.getExecutionId()), eq(versionId), eq("B"))).thenReturn(true);
        when(executionRepository.areAllPredecessorsSucceeded(eq(execution.getExecutionId()), eq(versionId), eq("C"))).thenReturn(true);
        when(executionRepository.areAllPredecessorsSucceeded(eq(execution.getExecutionId()), eq(versionId), eq("D"))).thenReturn(false);
        when(executionRepository.countNonSucceededNodes(execution.getExecutionId())).thenReturn(3);

        // 2. Task A completes successfully
        boolean handledA = executionService.onTaskCompleted(taskAId, true, null, null);
        assertTrue(handledA);

        assertEquals(WorkflowNodeStatus.SUCCEEDED, executionNodeStore.get("A").getStatus());
        // Fan-out: B and C must become READY and have tasks
        assertEquals(WorkflowNodeStatus.READY, executionNodeStore.get("B").getStatus());
        assertNotNull(executionNodeStore.get("B").getTaskId());
        assertEquals(WorkflowNodeStatus.READY, executionNodeStore.get("C").getStatus());
        assertNotNull(executionNodeStore.get("C").getTaskId());
        // D still waits (PENDING)
        assertEquals(WorkflowNodeStatus.PENDING, executionNodeStore.get("D").getStatus());
        assertEquals(WorkflowStatus.RUNNING, currentExecution.getStatus());

        UUID taskBId = executionNodeStore.get("B").getTaskId();
        UUID taskCId = executionNodeStore.get("C").getTaskId();

        // 3. Task B completes -> D still waits because C is not finished
        when(executionRepository.areAllPredecessorsSucceeded(eq(execution.getExecutionId()), eq(versionId), eq("D"))).thenReturn(false);
        when(executionRepository.countNonSucceededNodes(execution.getExecutionId())).thenReturn(2);

        executionService.onTaskCompleted(taskBId, true, null, null);
        assertEquals(WorkflowNodeStatus.SUCCEEDED, executionNodeStore.get("B").getStatus());
        assertEquals(WorkflowNodeStatus.PENDING, executionNodeStore.get("D").getStatus());

        // 4. Task C completes -> All predecessors of D (B and C) now succeeded! D becomes READY!
        when(executionRepository.areAllPredecessorsSucceeded(eq(execution.getExecutionId()), eq(versionId), eq("D"))).thenReturn(true);
        when(executionRepository.countNonSucceededNodes(execution.getExecutionId())).thenReturn(1);

        executionService.onTaskCompleted(taskCId, true, null, null);
        assertEquals(WorkflowNodeStatus.SUCCEEDED, executionNodeStore.get("C").getStatus());
        assertEquals(WorkflowNodeStatus.READY, executionNodeStore.get("D").getStatus());
        assertNotNull(executionNodeStore.get("D").getTaskId());

        UUID taskDId = executionNodeStore.get("D").getTaskId();

        // 5. Task D completes -> All 4 nodes succeeded! Workflow concludes SUCCEEDED!
        when(executionRepository.countNonSucceededNodes(execution.getExecutionId())).thenReturn(0);

        executionService.onTaskCompleted(taskDId, true, null, null);
        assertEquals(WorkflowNodeStatus.SUCCEEDED, executionNodeStore.get("D").getStatus());
        assertEquals(WorkflowStatus.SUCCEEDED, currentExecution.getStatus());
        assertNotNull(currentExecution.getCompletedAt());
    }

    @Test
    @DisplayName("Failure propagation: terminal node failure skips downstream nodes and fails workflow")
    void failurePropagationSkipsDownstreamNodes() {
        // Chain: A -> B -> C
        List<WorkflowNodeDefinition> nodes = List.of(node("A"), node("B"), node("C"));
        List<WorkflowEdge> edges = List.of(
                WorkflowEdge.of("A", "B"),
                WorkflowEdge.of("B", "C")
        );

        publishedVersion = WorkflowVersion.builder()
                .versionId(versionId)
                .workflowId(workflowId)
                .version(1)
                .status(WorkflowVersionStatus.PUBLISHED)
                .nodes(nodes)
                .edges(edges)
                .build();

        when(workflowRepository.findLatestPublishedVersion(workflowId)).thenReturn(Optional.of(publishedVersion));
        when(workflowRepository.findVersionById(versionId)).thenReturn(Optional.of(publishedVersion));

        // Start execution -> activates A
        executionService.startExecution(workflowId, null, "tenant-1");
        UUID taskAId = executionNodeStore.get("A").getTaskId();

        // Terminal failure on A (e.g. DLQ exhausted)
        boolean handled = executionService.onTaskCompleted(taskAId, false, "DLQ_MAX_ATTEMPTS", "Retries exhausted");
        assertTrue(handled);

        // A must be FAILED
        assertEquals(WorkflowNodeStatus.FAILED, executionNodeStore.get("A").getStatus());
        assertEquals("DLQ_MAX_ATTEMPTS", executionNodeStore.get("A").getErrorCode());

        // B and C must be SKIPPED
        assertEquals(WorkflowNodeStatus.SKIPPED, executionNodeStore.get("B").getStatus());
        assertEquals(WorkflowNodeStatus.SKIPPED, executionNodeStore.get("C").getStatus());

        // Workflow must be FAILED
        assertEquals(WorkflowStatus.FAILED, currentExecution.getStatus());
        assertNotNull(currentExecution.getCompletedAt());
    }

    @Test
    @DisplayName("Workflow cancellation cancels active tasks, pending nodes, and transitions to CANCELLED")
    void cancelExecutionCancelsTasksAndNodes() {
        setupDiamondDag();

        WorkflowExecution execution = executionService.startExecution(workflowId, null, "tenant-1");
        UUID taskAId = executionNodeStore.get("A").getTaskId();

        WorkflowExecution cancelled = executionService.cancelExecution(execution.getExecutionId(), "Operator aborted");

        assertEquals(WorkflowStatus.CANCELLED, cancelled.getStatus());
        assertEquals(WorkflowNodeStatus.CANCELLED, executionNodeStore.get("A").getStatus());
        assertEquals(WorkflowNodeStatus.CANCELLED, executionNodeStore.get("B").getStatus());
        assertEquals(WorkflowNodeStatus.CANCELLED, executionNodeStore.get("C").getStatus());
        assertEquals(WorkflowNodeStatus.CANCELLED, executionNodeStore.get("D").getStatus());

        // Underlying Chronos task for A must have been transitioned to CANCELLED
        verify(transactionalTaskService).transitionTask(eq(taskAId), eq(TaskStatus.CANCELLED), anyString(), any());
    }

    @Test
    @DisplayName("Execution Isolation: completing task for execution 1 does not affect execution 2")
    void executionIsolationBetweenConcurrentRuns() {
        setupDiamondDag();

        // Run 1
        WorkflowExecution exec1 = executionService.startExecution(workflowId, null, "tenant-1");
        WorkflowExecutionNode nodeA1 = executionNodeStore.get("A");
        UUID task1A = nodeA1.getTaskId();

        // Simulate separate task for a different execution
        UUID task2A = UUID.randomUUID();
        when(executionRepository.findExecutionNodeByTaskId(task2A)).thenReturn(Optional.empty());

        // Task from unknown/other execution returns false
        assertFalse(executionService.onTaskCompleted(task2A, true, null, null));
    }
}
