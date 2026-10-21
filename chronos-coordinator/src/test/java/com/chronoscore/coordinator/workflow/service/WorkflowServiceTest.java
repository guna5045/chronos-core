package com.chronoscore.coordinator.workflow.service;

import com.chronoscore.common.domain.workflow.WorkflowDefinition;
import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.domain.workflow.WorkflowVersion;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.WorkflowVersionStatus;
import com.chronoscore.common.exceptions.WorkflowNotFoundException;
import com.chronoscore.common.exceptions.WorkflowValidationException;
import com.chronoscore.coordinator.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowService Unit Tests")
class WorkflowServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        workflowService = new WorkflowService(workflowRepository);
    }

    private WorkflowNodeDefinition node(String id) {
        return WorkflowNodeDefinition.builder()
                .nodeId(id)
                .taskType("TEST_TASK")
                .priority(TaskPriority.NORMAL)
                .build();
    }

    @Test
    @DisplayName("Successfully creates a workflow definition")
    void createWorkflowSucceeds() {
        when(workflowRepository.saveWorkflow(any(WorkflowDefinition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowDefinition created = workflowService.createWorkflow("image-pipeline", "Processes customer images");

        assertNotNull(created);
        assertEquals("image-pipeline", created.getName());
        assertEquals("Processes customer images", created.getDescription());
        verify(workflowRepository).saveWorkflow(any(WorkflowDefinition.class));
    }

    @Test
    @DisplayName("Rejects blank workflow name")
    void rejectsBlankWorkflowName() {
        assertThrows(IllegalArgumentException.class, () -> workflowService.createWorkflow("", "desc"));
        assertThrows(IllegalArgumentException.class, () -> workflowService.createWorkflow("   ", "desc"));
        assertThrows(NullPointerException.class, () -> workflowService.createWorkflow(null, "desc"));
    }

    @Test
    @DisplayName("Creates draft version with auto-incremented version number")
    void createDraftVersionSucceeds() {
        UUID workflowId = UUID.randomUUID();
        WorkflowDefinition workflow = WorkflowDefinition.builder()
                .workflowId(workflowId)
                .name("data-etl")
                .build();

        when(workflowRepository.findWorkflowById(workflowId)).thenReturn(Optional.of(workflow));
        when(workflowRepository.findLatestPublishedVersion(workflowId)).thenReturn(Optional.empty());
        when(workflowRepository.saveVersion(any(WorkflowVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<WorkflowNodeDefinition> nodes = List.of(node("extract"), node("transform"), node("load"));
        List<WorkflowEdge> edges = List.of(
                WorkflowEdge.of("extract", "transform"),
                WorkflowEdge.of("transform", "load")
        );

        WorkflowVersion draft = workflowService.createDraftVersion(workflowId, nodes, edges);

        assertNotNull(draft);
        assertEquals(workflowId, draft.getWorkflowId());
        assertEquals(1, draft.getVersion());
        assertEquals(WorkflowVersionStatus.DRAFT, draft.getStatus());
        assertEquals(3, draft.getNodes().size());
        assertEquals(2, draft.getEdges().size());
    }

    @Test
    @DisplayName("Validates and publishes valid draft version")
    void publishValidVersionSucceeds() {
        UUID versionId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();

        List<WorkflowNodeDefinition> nodes = List.of(node("A"), node("B"));
        List<WorkflowEdge> edges = List.of(WorkflowEdge.of("A", "B"));

        WorkflowVersion draft = WorkflowVersion.builder()
                .versionId(versionId)
                .workflowId(workflowId)
                .version(1)
                .status(WorkflowVersionStatus.DRAFT)
                .nodes(nodes)
                .edges(edges)
                .build();

        when(workflowRepository.findVersionById(versionId)).thenReturn(Optional.of(draft));

        WorkflowVersion published = workflowService.publishVersion(versionId);

        assertNotNull(published);
        assertEquals(WorkflowVersionStatus.PUBLISHED, published.getStatus());
        verify(workflowRepository).updateVersionStatus(versionId, WorkflowVersionStatus.PUBLISHED);
    }

    @Test
    @DisplayName("Rejects publishing a version containing a cycle")
    void rejectsPublishingCyclicVersion() {
        UUID versionId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();

        // Cyclic DAG: A -> B -> A
        List<WorkflowNodeDefinition> nodes = List.of(node("A"), node("B"));
        List<WorkflowEdge> edges = List.of(
                WorkflowEdge.of("A", "B"),
                WorkflowEdge.of("B", "A")
        );

        WorkflowVersion draft = WorkflowVersion.builder()
                .versionId(versionId)
                .workflowId(workflowId)
                .version(1)
                .status(WorkflowVersionStatus.DRAFT)
                .nodes(nodes)
                .edges(edges)
                .build();

        when(workflowRepository.findVersionById(versionId)).thenReturn(Optional.of(draft));

        assertThrows(WorkflowValidationException.class, () -> workflowService.publishVersion(versionId));
        verify(workflowRepository, never()).updateVersionStatus(any(), any());
    }

    @Test
    @DisplayName("Deprecates published version")
    void deprecateVersionSucceeds() {
        UUID versionId = UUID.randomUUID();
        WorkflowVersion published = WorkflowVersion.builder()
                .versionId(versionId)
                .workflowId(UUID.randomUUID())
                .version(2)
                .status(WorkflowVersionStatus.PUBLISHED)
                .build();

        when(workflowRepository.findVersionById(versionId)).thenReturn(Optional.of(published));

        WorkflowVersion deprecated = workflowService.deprecateVersion(versionId);

        assertNotNull(deprecated);
        assertEquals(WorkflowVersionStatus.DEPRECATED, deprecated.getStatus());
        verify(workflowRepository).updateVersionStatus(versionId, WorkflowVersionStatus.DEPRECATED);
    }

    @Test
    @DisplayName("Rejects publishing deprecated version")
    void rejectsPublishingDeprecatedVersion() {
        UUID versionId = UUID.randomUUID();
        WorkflowVersion deprecated = WorkflowVersion.builder()
                .versionId(versionId)
                .workflowId(UUID.randomUUID())
                .version(1)
                .status(WorkflowVersionStatus.DEPRECATED)
                .build();

        when(workflowRepository.findVersionById(versionId)).thenReturn(Optional.of(deprecated));

        assertThrows(IllegalStateException.class, () -> workflowService.publishVersion(versionId));
    }
}
