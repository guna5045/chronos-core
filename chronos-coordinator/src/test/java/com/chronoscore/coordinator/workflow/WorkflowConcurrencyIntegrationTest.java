package com.chronoscore.coordinator.workflow;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowExecution;
import com.chronoscore.common.domain.workflow.WorkflowExecutionNode;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.domain.workflow.WorkflowVersion;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.WorkflowNodeStatus;
import com.chronoscore.common.enums.WorkflowStatus;
import com.chronoscore.common.enums.WorkflowVersionStatus;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import com.chronoscore.coordinator.workflow.repository.WorkflowAuditRepository;
import com.chronoscore.coordinator.workflow.repository.WorkflowExecutionRepository;
import com.chronoscore.coordinator.workflow.repository.WorkflowRepository;
import com.chronoscore.coordinator.workflow.service.WorkflowExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Workflow Concurrency & Idempotency Integration Tests")
class WorkflowConcurrencyIntegrationTest {

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
    private WorkflowVersion diamondVersion;
    private final Map<String, WorkflowExecutionNode> executionNodeStore = new ConcurrentHashMap<>();
    private final Map<UUID, WorkflowExecutionNode> taskToNodeMap = new ConcurrentHashMap<>();
    private final List<Task> submittedTasks = Collections.synchronizedList(new ArrayList<>());
    private volatile WorkflowExecution currentExecution;

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
        executionNodeStore.clear();
        taskToNodeMap.clear();
        submittedTasks.clear();

        // Thread-safe repository mocking
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

        when(executionRepository.claimNodeForActivation(any(UUID.class), anyString(), any(Instant.class)))
                .thenAnswer(inv -> {
                    String nodeId = inv.getArgument(1);
                    Instant startedAt = inv.getArgument(2);
                    java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean(false);
                    executionNodeStore.compute(nodeId, (k, node) -> {
                        if (node != null && node.getStatus() == WorkflowNodeStatus.PENDING) {
                            claimed.set(true);
                            return node.toBuilder()
                                    .status(WorkflowNodeStatus.READY)
                                    .startedAt(startedAt)
                                    .version(node.getVersion() + 1)
                                    .build();
                        }
                        return node;
                    });
                    return claimed.get();
                });

        doAnswer(inv -> {
            String nodeId = inv.getArgument(1);
            UUID taskId = inv.getArgument(2);
            executionNodeStore.computeIfPresent(nodeId, (k, node) -> {
                WorkflowExecutionNode updated = node.toBuilder().taskId(taskId).build();
                taskToNodeMap.put(taskId, updated);
                return updated;
            });
            return null;
        }).when(executionRepository).linkTaskToNode(any(UUID.class), anyString(), any(UUID.class));

        when(executionRepository.findExecutionNodes(any(UUID.class)))
                .thenAnswer(inv -> new ArrayList<>(executionNodeStore.values()));

        when(transactionalTaskService.submitTask(any(Task.class), anyString(), any()))
                .thenAnswer(inv -> {
                    Task t = inv.getArgument(0);
                    submittedTasks.add(t);
                    return t;
                });

        // Set up Diamond DAG: A -> B, A -> C, B -> D, C -> D
        List<WorkflowNodeDefinition> nodes = List.of(
                node("A"), node("B"), node("C"), node("D")
        );
        List<WorkflowEdge> edges = List.of(
                WorkflowEdge.of("A", "B"),
                WorkflowEdge.of("A", "C"),
                WorkflowEdge.of("B", "D"),
                WorkflowEdge.of("C", "D")
        );

        diamondVersion = WorkflowVersion.builder()
                .versionId(versionId)
                .workflowId(workflowId)
                .version(1)
                .status(WorkflowVersionStatus.PUBLISHED)
                .nodes(nodes)
                .edges(edges)
                .build();

        when(workflowRepository.findLatestPublishedVersion(workflowId)).thenReturn(Optional.of(diamondVersion));
        when(workflowRepository.findVersionById(versionId)).thenReturn(Optional.of(diamondVersion));
    }

    private WorkflowNodeDefinition node(String id) {
        return WorkflowNodeDefinition.builder()
                .nodeId(id)
                .taskType("TASK_" + id)
                .priority(TaskPriority.NORMAL)
                .build();
    }

    @Test
    @DisplayName("Concurrent fan-in: B and C complete simultaneously, D is activated exactly once")
    void concurrentFanInActivatesDownstreamExactlyOnce() throws InterruptedException {
        // 1. Start execution -> activates A
        WorkflowExecution execution = executionService.startExecution(workflowId, null, "tenant-1");
        UUID taskAId = executionNodeStore.get("A").getTaskId();

        // 2. Complete A -> B and C become READY
        when(executionRepository.areAllPredecessorsSucceeded(any(), any(), eq("B"))).thenReturn(true);
        when(executionRepository.areAllPredecessorsSucceeded(any(), any(), eq("C"))).thenReturn(true);
        when(executionRepository.countNonSucceededNodes(any())).thenReturn(3);

        executionService.onTaskCompleted(taskAId, true, null, null);

        UUID taskBId = executionNodeStore.get("B").getTaskId();
        UUID taskCId = executionNodeStore.get("C").getTaskId();
        assertNotNull(taskBId);
        assertNotNull(taskCId);

        // Predecessor evaluation for D: both B and C are SUCCEEDED once both threads finish
        when(executionRepository.areAllPredecessorsSucceeded(any(), any(), eq("D"))).thenReturn(true);

        // 3. Concurrently complete B and C
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                barrier.await();
                boolean res = executionService.onTaskCompleted(taskBId, true, null, null);
                if (res) successCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                barrier.await();
                boolean res = executionService.onTaskCompleted(taskCId, true, null, null);
                if (res) successCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Concurrent tasks timed out");
        executor.shutdown();

        assertEquals(2, successCount.get());

        // 4. Verify node D state: D must be READY
        WorkflowExecutionNode nodeD = executionNodeStore.get("D");
        assertNotNull(nodeD);
        assertEquals(WorkflowNodeStatus.READY, nodeD.getStatus());

        // 5. Verify task creation: Total submitted tasks must be exactly 4 (A, B, C, D)
        // Exactly one task created for D!
        long dTasks = submittedTasks.stream()
                .filter(t -> t.getTaskType().equals("TASK_D"))
                .count();
        assertEquals(1, dTasks, "Downstream task D must be activated exactly once");
    }

    @Test
    @DisplayName("Idempotency: Processing identical task completion multiple times creates zero duplicate tasks")
    void duplicateTaskCompletionIsIdempotent() {
        WorkflowExecution execution = executionService.startExecution(workflowId, null, "tenant-1");
        UUID taskAId = executionNodeStore.get("A").getTaskId();

        when(executionRepository.areAllPredecessorsSucceeded(any(), any(), eq("B"))).thenReturn(true);
        when(executionRepository.areAllPredecessorsSucceeded(any(), any(), eq("C"))).thenReturn(true);
        when(executionRepository.countNonSucceededNodes(any())).thenReturn(3);

        // First completion call
        boolean firstCall = executionService.onTaskCompleted(taskAId, true, null, null);
        assertTrue(firstCall);

        int tasksAfterFirst = submittedTasks.size();

        // Second duplicate completion call for same task
        boolean secondCall = executionService.onTaskCompleted(taskAId, true, null, null);
        assertTrue(secondCall); // Returns true (acknowledged)

        // Verifies no additional tasks were submitted
        assertEquals(tasksAfterFirst, submittedTasks.size(), "Duplicate completion must not create duplicate tasks");
    }
}
