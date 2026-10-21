package com.chronoscore.coordinator.api.controller;

import com.chronoscore.ai.dto.AiActionRisk;
import com.chronoscore.ai.dto.AiActionStatus;
import com.chronoscore.ai.dto.AiActionType;
import com.chronoscore.ai.dto.AiEvidenceRecord;
import com.chronoscore.ai.dto.AiIncidentSeverity;
import com.chronoscore.ai.dto.AiOperationalAction;
import com.chronoscore.ai.dto.AiProposalStatus;
import com.chronoscore.ai.dto.AiStatusResponse;
import com.chronoscore.ai.dto.AiWorkflowProposal;
import com.chronoscore.ai.dto.GenerateWorkflowRequest;
import com.chronoscore.ai.dto.IncidentAnalysisRequest;
import com.chronoscore.ai.dto.IncidentAnalysisResponse;
import com.chronoscore.ai.dto.IncidentScope;
import com.chronoscore.ai.policy.AiActionExpiredException;
import com.chronoscore.ai.policy.StaleActionStateException;
import com.chronoscore.ai.ports.AiWorkflowOperationsPort;
import com.chronoscore.ai.service.AiOperationsService;
import com.chronoscore.ai.service.AiStatusService;
import com.chronoscore.ai.service.AiWorkflowService;
import com.chronoscore.coordinator.api.advice.GlobalExceptionHandler;
import com.chronoscore.coordinator.api.security.DevJwtTokenHelper;
import com.chronoscore.coordinator.api.security.JwtSecurityProperties;
import com.chronoscore.coordinator.api.security.SecurityConfig;
import com.chronoscore.coordinator.api.security.TenantSecurityContext;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AiController.class)
@Import({SecurityConfig.class, JwtSecurityProperties.class, DevJwtTokenHelper.class, TenantSecurityContext.class, GlobalExceptionHandler.class})
class AiControllerRestTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DevJwtTokenHelper tokenHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiWorkflowService workflowService;

    @MockBean
    private AiOperationsService operationsService;

    @MockBean
    private AiStatusService statusService;

    private String getOperatorToken() {
        return tokenHelper.generateToken("test-operator", "tenant-alpha", List.of("OPERATOR"));
    }

    private String getAdminToken() {
        return tokenHelper.generateToken("test-admin", "tenant-alpha", List.of("ADMIN"));
    }

    private String getViewerToken() {
        return tokenHelper.generateToken("test-viewer", "tenant-alpha", List.of("VIEWER"));
    }

    private String getOtherTenantToken() {
        return tokenHelper.generateToken("test-operator-beta", "tenant-beta", List.of("OPERATOR"));
    }

    @Test
    @DisplayName("GET /api/v1/ai/status returns 200 OK with AI operational status")
    void shouldReturnAiStatus() throws Exception {
        String token = getViewerToken();
        when(statusService.getStatus()).thenReturn(new AiStatusResponse(true, "stub", "gpt-4o-mini", true));

        mockMvc.perform(get("/api/v1/ai/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.provider").value("stub"))
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/ai/workflows/generate generates proposal for OPERATOR")
    void shouldGenerateWorkflowProposalForOperator() throws Exception {
        String token = getOperatorToken();
        UUID proposalId = UUID.randomUUID();

        GenerateWorkflowRequest request = new GenerateWorkflowRequest(
                "Process incoming orders", "order-pipeline", "tenant-alpha"
        );

        AiWorkflowProposal proposal = AiWorkflowProposal.builder()
                .proposalId(proposalId)
                .tenantId("tenant-alpha")
                .workflowName("order-pipeline")
                .explanation("Extract, validate, and store orders.")
                .valid(true)
                .status(AiProposalStatus.PENDING_REVIEW)
                .build();

        when(workflowService.generateProposal(any(), eq("tenant-alpha"))).thenReturn(proposal);

        mockMvc.perform(post("/api/v1/ai/workflows/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalId").value(proposalId.toString()))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.workflowName").value("order-pipeline"));
    }

    @Test
    @DisplayName("POST /api/v1/ai/workflows/generate rejects VIEWER with 403 Forbidden")
    void shouldRejectViewerFromGeneratingWorkflow() throws Exception {
        String token = getViewerToken();
        GenerateWorkflowRequest request = new GenerateWorkflowRequest("Some prompt", "some-wf", "tenant-alpha");

        mockMvc.perform(post("/api/v1/ai/workflows/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/ai/workflows/{id}/approve materializes draft version for OPERATOR")
    void shouldApproveWorkflowProposal() throws Exception {
        String token = getOperatorToken();
        UUID proposalId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();

        AiWorkflowProposal proposal = AiWorkflowProposal.builder()
                .proposalId(proposalId)
                .tenantId("tenant-alpha")
                .workflowName("order-pipeline")
                .status(AiProposalStatus.PENDING_REVIEW)
                .build();
        when(workflowService.getProposal(proposalId)).thenReturn(Optional.of(proposal));

        when(workflowService.approveProposal(eq(proposalId), any(), any(), eq("tenant-alpha"))).thenReturn(
                new AiWorkflowOperationsPort.WorkflowCreatedResult(workflowId, "order-pipeline", 1, "DRAFT")
        );

        mockMvc.perform(post("/api/v1/ai/workflows/" + proposalId + "/approve")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(workflowId.toString()))
                .andExpect(jsonPath("$.name").value("order-pipeline"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("POST /api/v1/ai/workflows/{id}/approve rejects cross-tenant caller with 403 Forbidden")
    void shouldRejectCrossTenantWorkflowApproval() throws Exception {
        String otherTenantToken = getOtherTenantToken(); // tenant-beta
        UUID proposalId = UUID.randomUUID();

        // Proposal belongs to tenant-alpha
        AiWorkflowProposal proposal = AiWorkflowProposal.builder()
                .proposalId(proposalId)
                .tenantId("tenant-alpha")
                .workflowName("alpha-pipeline")
                .status(AiProposalStatus.PENDING_REVIEW)
                .build();
        when(workflowService.getProposal(proposalId)).thenReturn(Optional.of(proposal));

        mockMvc.perform(post("/api/v1/ai/workflows/" + proposalId + "/approve")
                        .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/ai/incidents/analyze performs incident diagnosis")
    void shouldAnalyzeIncident() throws Exception {
        String token = getOperatorToken();
        UUID analysisId = UUID.randomUUID();

        IncidentAnalysisRequest request = new IncidentAnalysisRequest(IncidentScope.CLUSTER, null, "tenant-alpha");
        IncidentAnalysisResponse response = new IncidentAnalysisResponse(
                analysisId,
                IncidentScope.CLUSTER,
                null,
                "Coordinator leader healthy, workers operating at normal capacity",
                AiIncidentSeverity.LOW,
                List.of(new AiEvidenceRecord("cluster", "coord-01", "Leader healthy", "ACTIVE")),
                List.of("Normal operation"),
                List.of(),
                Instant.now()
        );

        when(operationsService.analyzeIncident(any(), eq("tenant-alpha"))).thenReturn(response);

        mockMvc.perform(post("/api/v1/ai/incidents/analyze")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisId").value(analysisId.toString()))
                .andExpect(jsonPath("$.severity").value("LOW"));
    }

    @Test
    @DisplayName("POST /api/v1/ai/actions/{id}/approve executes action and returns 200 OK")
    void shouldApproveAction() throws Exception {
        String token = getOperatorToken();
        UUID actionId = UUID.randomUUID();

        AiOperationalAction action = AiOperationalAction.builder()
                .actionId(actionId)
                .tenantId("tenant-alpha")
                .title("Retry task")
                .actionType(AiActionType.RETRY_TASK)
                .targetId(UUID.randomUUID().toString())
                .status(AiActionStatus.PENDING_APPROVAL)
                .build();
        when(operationsService.getAction(actionId)).thenReturn(Optional.of(action));

        AiOperationalAction executed = AiOperationalAction.builder()
                .actionId(actionId)
                .tenantId("tenant-alpha")
                .title("Retry task")
                .actionType(AiActionType.RETRY_TASK)
                .targetId(action.targetId())
                .status(AiActionStatus.EXECUTED)
                .executionResult("Task retried")
                .build();
        when(operationsService.approveAction(eq(actionId), any(), any(), eq("tenant-alpha"))).thenReturn(executed);

        mockMvc.perform(post("/api/v1/ai/actions/" + actionId + "/approve")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    @Test
    @DisplayName("POST /api/v1/ai/actions/{id}/approve returns 409 Conflict when target state is stale")
    void shouldReturn409OnStaleState() throws Exception {
        String token = getOperatorToken();
        UUID actionId = UUID.randomUUID();

        AiOperationalAction action = AiOperationalAction.builder()
                .actionId(actionId)
                .tenantId("tenant-alpha")
                .title("Retry task")
                .actionType(AiActionType.RETRY_TASK)
                .targetId(UUID.randomUUID().toString())
                .status(AiActionStatus.PENDING_APPROVAL)
                .build();
        when(operationsService.getAction(actionId)).thenReturn(Optional.of(action));

        when(operationsService.approveAction(eq(actionId), any(), any(), eq("tenant-alpha")))
                .thenThrow(new StaleActionStateException("Target task is no longer in FAILED state (current: SUCCEEDED)"));

        mockMvc.perform(post("/api/v1/ai/actions/" + actionId + "/approve")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_ACTION_STATE"));
    }

    @Test
    @DisplayName("POST /api/v1/ai/actions/{id}/approve returns 410 Gone when recommendation expired")
    void shouldReturn410OnExpiredAction() throws Exception {
        String token = getOperatorToken();
        UUID actionId = UUID.randomUUID();

        AiOperationalAction action = AiOperationalAction.builder()
                .actionId(actionId)
                .tenantId("tenant-alpha")
                .title("Retry task")
                .actionType(AiActionType.RETRY_TASK)
                .targetId(UUID.randomUUID().toString())
                .status(AiActionStatus.PENDING_APPROVAL)
                .build();
        when(operationsService.getAction(actionId)).thenReturn(Optional.of(action));

        when(operationsService.approveAction(eq(actionId), any(), any(), eq("tenant-alpha")))
                .thenThrow(new AiActionExpiredException("Action recommendation has expired"));

        mockMvc.perform(post("/api/v1/ai/actions/" + actionId + "/approve")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AI_ACTION_EXPIRED"));
    }
}
