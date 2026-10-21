package com.chronoscore.coordinator.api.controller;

import com.chronoscore.common.domain.workflow.WorkflowDefinition;
import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowExecution;
import com.chronoscore.common.domain.workflow.WorkflowExecutionNode;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.domain.workflow.WorkflowVersion;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.WorkflowNodeStatus;
import com.chronoscore.common.enums.WorkflowStatus;
import com.chronoscore.common.enums.WorkflowVersionStatus;
import com.chronoscore.common.exceptions.WorkflowValidationException;
import com.chronoscore.coordinator.api.advice.GlobalExceptionHandler;
import com.chronoscore.coordinator.api.dto.request.CreateWorkflowRequest;
import com.chronoscore.coordinator.api.dto.request.CreateWorkflowVersionRequest;
import com.chronoscore.coordinator.api.dto.request.ExecuteWorkflowRequest;
import com.chronoscore.coordinator.api.security.DevJwtTokenHelper;
import com.chronoscore.coordinator.api.security.JwtSecurityProperties;
import com.chronoscore.coordinator.api.security.SecurityConfig;
import com.chronoscore.coordinator.api.security.TenantSecurityContext;
import com.chronoscore.coordinator.workflow.service.WorkflowExecutionService;
import com.chronoscore.coordinator.workflow.service.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WorkflowController.class)
@Import({SecurityConfig.class, JwtSecurityProperties.class, DevJwtTokenHelper.class, TenantSecurityContext.class, GlobalExceptionHandler.class})
class WorkflowControllerRestTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DevJwtTokenHelper tokenHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkflowService workflowService;

    @MockBean
    private WorkflowExecutionService executionService;

    private String getAdminToken() {
        return tokenHelper.generateToken("admin-user", "tenant-alpha", List.of("ADMIN"));
    }

    private String getOperatorToken() {
        return tokenHelper.generateToken("op-user", "tenant-alpha", List.of("OPERATOR"));
    }

    @Test
    @DisplayName("POST /api/v1/workflows creates workflow definition (201 Created)")
    void createWorkflowSuccess() throws Exception {
        String token = getAdminToken();
        CreateWorkflowRequest request = new CreateWorkflowRequest("Video Pipeline", "Transcode and publish");

        UUID workflowId = UUID.randomUUID();
        WorkflowDefinition def = WorkflowDefinition.builder()
                .workflowId(workflowId)
                .name("Video Pipeline")
                .description("Transcode and publish")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(workflowService.createWorkflow("Video Pipeline", "Transcode and publish")).thenReturn(def);

        mockMvc.perform(post("/api/v1/workflows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.workflowId").value(workflowId.toString()))
                .andExpect(jsonPath("$.name").value("Video Pipeline"));
    }

    @Test
    @DisplayName("GET /api/v1/workflows lists paginated definitions (200 OK)")
    void listWorkflowsPaginated() throws Exception {
        String token = getOperatorToken();
        WorkflowDefinition def = WorkflowDefinition.builder()
                .workflowId(UUID.randomUUID())
                .name("Pipeline A")
                .description("Desc")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(workflowService.countWorkflows()).thenReturn(1);
        when(workflowService.listWorkflows(20, 0)).thenReturn(List.of(def));

        mockMvc.perform(get("/api/v1/workflows?page=0&size=20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Pipeline A"));
    }

    @Test
    @DisplayName("POST /api/v1/workflows/{id}/versions creates version with valid DAG (201 Created)")
    void createWorkflowVersionSuccess() throws Exception {
        String token = getAdminToken();
        UUID workflowId = UUID.randomUUID();

        List<CreateWorkflowVersionRequest.WorkflowNodeRequest> nodes = List.of(
                new CreateWorkflowVersionRequest.WorkflowNodeRequest("nodeA", "DOWNLOAD", "{}", TaskPriority.NORMAL, null, 3),
                new CreateWorkflowVersionRequest.WorkflowNodeRequest("nodeB", "PROCESS", "{}", TaskPriority.NORMAL, null, 3)
        );
        List<CreateWorkflowVersionRequest.WorkflowEdgeRequest> edges = List.of(
                new CreateWorkflowVersionRequest.WorkflowEdgeRequest("nodeA", "nodeB")
        );

        CreateWorkflowVersionRequest request = new CreateWorkflowVersionRequest(nodes, edges);

        UUID versionId = UUID.randomUUID();
        WorkflowVersion version = WorkflowVersion.builder()
                .versionId(versionId)
                .workflowId(workflowId)
                .version(1)
                .status(WorkflowVersionStatus.DRAFT)
                .nodes(request.toNodeDefinitions())
                .edges(request.toEdgeDefinitions())
                .createdAt(Instant.now())
                .build();

        when(workflowService.createDraftVersion(eq(workflowId), any(), any())).thenReturn(version);

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.nodes.length()").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/workflows/{id}/versions with cycle returns 400 Bad Request INVALID_DAG")
    void createWorkflowVersionWithCycleReturns400() throws Exception {
        String token = getAdminToken();
        UUID workflowId = UUID.randomUUID();

        List<CreateWorkflowVersionRequest.WorkflowNodeRequest> nodes = List.of(
                new CreateWorkflowVersionRequest.WorkflowNodeRequest("nodeA", "A", "{}", TaskPriority.NORMAL, null, 3),
                new CreateWorkflowVersionRequest.WorkflowNodeRequest("nodeB", "B", "{}", TaskPriority.NORMAL, null, 3)
        );
        List<CreateWorkflowVersionRequest.WorkflowEdgeRequest> edges = List.of(
                new CreateWorkflowVersionRequest.WorkflowEdgeRequest("nodeA", "nodeB"),
                new CreateWorkflowVersionRequest.WorkflowEdgeRequest("nodeB", "nodeA")
        );

        CreateWorkflowVersionRequest request = new CreateWorkflowVersionRequest(nodes, edges);

        when(workflowService.createDraftVersion(eq(workflowId), any(), any()))
                .thenThrow(new WorkflowValidationException("Cycle detected involving node: nodeA"));

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DAG"))
                .andExpect(jsonPath("$.message").value("Cycle detected involving node: nodeA"));
    }

    @Test
    @DisplayName("POST /api/v1/workflows/{id}/versions/{v}/publish publishes version (200 OK)")
    void publishVersionSuccess() throws Exception {
        String token = getAdminToken();
        UUID workflowId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        WorkflowVersion draft = WorkflowVersion.builder()
                .versionId(versionId)
                .workflowId(workflowId)
                .version(1)
                .status(WorkflowVersionStatus.DRAFT)
                .nodes(Collections.emptyList())
                .edges(Collections.emptyList())
                .createdAt(Instant.now())
                .build();

        WorkflowVersion published = draft.toBuilder()
                .status(WorkflowVersionStatus.PUBLISHED)
                .build();

        when(workflowService.getVersionByNumber(workflowId, 1)).thenReturn(Optional.of(draft));
        when(workflowService.publishVersion(versionId)).thenReturn(published);

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/versions/1/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    @DisplayName("POST /api/v1/workflows/{id}/execute starts workflow execution (201 Created)")
    void executeWorkflowSuccess() throws Exception {
        String token = getOperatorToken();
        UUID workflowId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        WorkflowExecution execution = WorkflowExecution.builder()
                .executionId(executionId)
                .workflowId(workflowId)
                .versionId(UUID.randomUUID())
                .version(1)
                .tenantId("tenant-alpha")
                .status(WorkflowStatus.RUNNING)
                .createdAt(Instant.now())
                .build();

        ExecuteWorkflowRequest request = new ExecuteWorkflowRequest(1, null, "tenant-alpha");

        when(executionService.startExecution(eq(workflowId), eq(1), eq("tenant-alpha"))).thenReturn(execution);

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/execute")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    @DisplayName("GET /api/v1/workflow-executions/{id} returns execution status and constituent nodes")
    void getExecutionDetails() throws Exception {
        String token = getOperatorToken();
        UUID executionId = UUID.randomUUID();

        WorkflowExecution execution = WorkflowExecution.builder()
                .executionId(executionId)
                .workflowId(UUID.randomUUID())
                .versionId(UUID.randomUUID())
                .version(1)
                .tenantId("tenant-alpha")
                .status(WorkflowStatus.RUNNING)
                .createdAt(Instant.now())
                .build();

        WorkflowExecutionNode node = WorkflowExecutionNode.builder()
                .executionId(executionId)
                .nodeId("nodeA")
                .status(WorkflowNodeStatus.SUCCEEDED)
                .build();

        when(executionService.getExecution(executionId)).thenReturn(Optional.of(execution));
        when(executionService.getExecutionNodes(executionId)).thenReturn(List.of(node));

        mockMvc.perform(get("/api/v1/workflow-executions/" + executionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.execution.status").value("RUNNING"))
                .andExpect(jsonPath("$.nodes[0].nodeId").value("nodeA"))
                .andExpect(jsonPath("$.nodes[0].status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("POST /api/v1/workflow-executions/{id}/cancel cancels running execution (200 OK)")
    void cancelWorkflowExecutionSuccess() throws Exception {
        String token = getOperatorToken();
        UUID executionId = UUID.randomUUID();

        WorkflowExecution execution = WorkflowExecution.builder()
                .executionId(executionId)
                .workflowId(UUID.randomUUID())
                .versionId(UUID.randomUUID())
                .version(1)
                .tenantId("tenant-alpha")
                .status(WorkflowStatus.RUNNING)
                .createdAt(Instant.now())
                .build();

        WorkflowExecution cancelled = execution.toBuilder().status(WorkflowStatus.CANCELLED).build();

        when(executionService.getExecution(executionId)).thenReturn(Optional.of(execution));
        when(executionService.cancelExecution(eq(executionId), anyString())).thenReturn(cancelled);

        mockMvc.perform(post("/api/v1/workflow-executions/" + executionId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
