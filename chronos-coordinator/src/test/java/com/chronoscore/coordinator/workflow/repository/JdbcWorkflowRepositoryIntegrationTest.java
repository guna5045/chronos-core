package com.chronoscore.coordinator.workflow.repository;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.domain.workflow.WorkflowDefinition;
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
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("JdbcWorkflowRepository Integration Tests")
class JdbcWorkflowRepositoryIntegrationTest {

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private WorkflowAuditRepository auditRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Test
    @DisplayName("Persists and retrieves workflow definition, version, nodes, edges, and execution instances")
    void testWorkflowDatabaseOperations() {
        // 1. Save workflow
        UUID workflowId = UUID.randomUUID();
        WorkflowDefinition workflow = WorkflowDefinition.builder()
                .workflowId(workflowId)
                .name("db-test-pipeline")
                .description("Database integration test pipeline")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        workflowRepository.saveWorkflow(workflow);

        Optional<WorkflowDefinition> fetchedWf = workflowRepository.findWorkflowById(workflowId);
        assertThat(fetchedWf).isPresent();
        assertThat(fetchedWf.get().getName()).isEqualTo("db-test-pipeline");

        Optional<WorkflowDefinition> fetchedByName = workflowRepository.findWorkflowByName("db-test-pipeline");
        assertThat(fetchedByName).isPresent();

        // 2. Save version with diamond DAG
        UUID versionId = UUID.randomUUID();
        List<WorkflowNodeDefinition> nodes = List.of(
                WorkflowNodeDefinition.builder().nodeId("A").taskType("DOWNLOAD").priority(TaskPriority.HIGH).timeout(Duration.ofSeconds(30)).build(),
                WorkflowNodeDefinition.builder().nodeId("B").taskType("PROCESS").priority(TaskPriority.NORMAL).build(),
                WorkflowNodeDefinition.builder().nodeId("C").taskType("ANALYZE").priority(TaskPriority.NORMAL).build(),
                WorkflowNodeDefinition.builder().nodeId("D").taskType("NOTIFY").priority(TaskPriority.LOW).build()
        );
        List<WorkflowEdge> edges = List.of(
                WorkflowEdge.of("A", "B"),
                WorkflowEdge.of("A", "C"),
                WorkflowEdge.of("B", "D"),
                WorkflowEdge.of("C", "D")
        );

        WorkflowVersion version = WorkflowVersion.builder()
                .versionId(versionId)
                .workflowId(workflowId)
                .version(1)
                .status(WorkflowVersionStatus.DRAFT)
                .nodes(nodes)
                .edges(edges)
                .createdAt(Instant.now())
                .build();

        workflowRepository.saveVersion(version);

        Optional<WorkflowVersion> fetchedVer = workflowRepository.findVersionById(versionId);
        assertThat(fetchedVer).isPresent();
        assertThat(fetchedVer.get().getNodes()).hasSize(4);
        assertThat(fetchedVer.get().getEdges()).hasSize(4);

        // 3. Publish version
        workflowRepository.updateVersionStatus(versionId, WorkflowVersionStatus.PUBLISHED);
        Optional<WorkflowVersion> published = workflowRepository.findLatestPublishedVersion(workflowId);
        assertThat(published).isPresent();
        assertThat(published.get().getStatus()).isEqualTo(WorkflowVersionStatus.PUBLISHED);

        // 4. Save workflow execution
        UUID executionId = UUID.randomUUID();
        WorkflowExecution execution = WorkflowExecution.builder()
                .executionId(executionId)
                .workflowId(workflowId)
                .versionId(versionId)
                .status(WorkflowStatus.RUNNING)
                .tenantId("tenant-db-test")
                .createdAt(Instant.now())
                .startedAt(Instant.now())
                .version(0L)
                .build();

        executionRepository.saveExecution(execution);

        Optional<WorkflowExecution> fetchedExec = executionRepository.findExecutionById(executionId);
        assertThat(fetchedExec).isPresent();
        assertThat(fetchedExec.get().getStatus()).isEqualTo(WorkflowStatus.RUNNING);

        // 5. Save execution nodes
        List<WorkflowExecutionNode> execNodes = List.of(
                WorkflowExecutionNode.builder().executionId(executionId).nodeId("A").status(WorkflowNodeStatus.PENDING).build(),
                WorkflowExecutionNode.builder().executionId(executionId).nodeId("B").status(WorkflowNodeStatus.PENDING).build(),
                WorkflowExecutionNode.builder().executionId(executionId).nodeId("C").status(WorkflowNodeStatus.PENDING).build(),
                WorkflowExecutionNode.builder().executionId(executionId).nodeId("D").status(WorkflowNodeStatus.PENDING).build()
        );
        executionRepository.saveExecutionNodes(execNodes);

        List<WorkflowExecutionNode> loadedNodes = executionRepository.findExecutionNodes(executionId);
        assertThat(loadedNodes).hasSize(4);

        // 6. Test areAllPredecessorsSucceeded query
        // A has no predecessors -> should be true
        assertThat(executionRepository.areAllPredecessorsSucceeded(executionId, versionId, "A")).isTrue();
        // B has predecessor A (which is PENDING) -> should be false
        assertThat(executionRepository.areAllPredecessorsSucceeded(executionId, versionId, "B")).isFalse();

        // Update A to SUCCEEDED with a persisted task to satisfy foreign key constraint
        Task task = Task.builder()
                .taskId(UUID.randomUUID())
                .tenantId("tenant-db-test")
                .taskType("DOWNLOAD")
                .payload("{\"input\":\"data\"}")
                .status(TaskStatus.SUCCEEDED)
                .priority(TaskPriority.HIGH)
                .createdAt(Instant.now())
                .build();
        taskRepository.save(task);
        UUID taskA = task.getTaskId();
        WorkflowExecutionNode nodeA = executionRepository.findExecutionNode(executionId, "A").get();
        WorkflowExecutionNode updatedA = nodeA.toBuilder()
                .taskId(taskA)
                .status(WorkflowNodeStatus.SUCCEEDED)
                .completedAt(Instant.now())
                .build();
        executionRepository.updateExecutionNode(updatedA);

        // Now A is SUCCEEDED, so B and C predecessors are satisfied
        assertThat(executionRepository.areAllPredecessorsSucceeded(executionId, versionId, "B")).isTrue();
        assertThat(executionRepository.areAllPredecessorsSucceeded(executionId, versionId, "C")).isTrue();
        // D requires B and C (which are still PENDING) -> should be false
        assertThat(executionRepository.areAllPredecessorsSucceeded(executionId, versionId, "D")).isFalse();

        // Lookup node by taskId
        Optional<WorkflowExecutionNode> nodeByTask = executionRepository.findExecutionNodeByTaskId(taskA);
        assertThat(nodeByTask).isPresent();
        assertThat(nodeByTask.get().getNodeId()).isEqualTo("A");

        // 7. Non-succeeded count
        assertThat(executionRepository.countNonSucceededNodes(executionId)).isEqualTo(3);

        // 8. Audit log append
        auditRepository.appendAudit(executionId, "PENDING", "RUNNING", "Started", "meta");
    }
}
