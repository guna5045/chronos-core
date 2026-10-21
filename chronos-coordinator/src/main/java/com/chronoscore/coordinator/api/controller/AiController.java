package com.chronoscore.coordinator.api.controller;

import com.chronoscore.ai.dto.AiActionStatus;
import com.chronoscore.ai.dto.AiOperationalAction;
import com.chronoscore.ai.dto.AiStatusResponse;
import com.chronoscore.ai.dto.AiWorkflowProposal;
import com.chronoscore.ai.dto.GenerateWorkflowRequest;
import com.chronoscore.ai.dto.IncidentAnalysisRequest;
import com.chronoscore.ai.dto.IncidentAnalysisResponse;
import com.chronoscore.ai.ports.AiWorkflowOperationsPort;
import com.chronoscore.ai.service.AiOperationsService;
import com.chronoscore.ai.service.AiStatusService;
import com.chronoscore.ai.service.AiWorkflowService;
import com.chronoscore.coordinator.api.security.TenantSecurityContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller exposing controlled AI Workflow and Operations agent capabilities.
 * <p>
 * Key Invariants:
 * 1. AI is strictly advisory: Proposals and recommendations require human operator approval.
 * 2. RBAC Enforced: VIEWER is read-only; OPERATOR and ADMIN can generate and approve actions.
 * 3. Tenant Isolation Enforced: Users cannot generate, view, or approve cross-tenant proposals/actions.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiWorkflowService workflowService;
    private final AiOperationsService operationsService;
    private final AiStatusService statusService;
    private final TenantSecurityContext securityContext;

    public AiController(AiWorkflowService workflowService,
                        AiOperationsService operationsService,
                        AiStatusService statusService,
                        TenantSecurityContext securityContext) {
        this.workflowService = Objects.requireNonNull(workflowService, "workflowService must not be null");
        this.operationsService = Objects.requireNonNull(operationsService, "operationsService must not be null");
        this.statusService = Objects.requireNonNull(statusService, "statusService must not be null");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext must not be null");
    }

    /**
     * Inspects operational status and availability of the AI layer.
     */
    @GetMapping("/status")
    public ResponseEntity<AiStatusResponse> getStatus() {
        return ResponseEntity.ok(statusService.getStatus());
    }

    // --- AI Workflow Agent Endpoints ---

    /**
     * Generates a structured workflow DAG proposal from natural language description.
     */
    @PostMapping("/workflows/generate")
    public ResponseEntity<AiWorkflowProposal> generateWorkflowProposal(
            @Valid @RequestBody GenerateWorkflowRequest request) {

        String effectiveTenantId = securityContext.resolveEffectiveTenantId(request.tenantId());
        AiWorkflowProposal proposal = workflowService.generateProposal(request, effectiveTenantId);

        return ResponseEntity.ok(proposal);
    }

    /**
     * Lists active/pending workflow proposals for the authenticated tenant.
     */
    @GetMapping("/workflows")
    public ResponseEntity<List<AiWorkflowProposal>> listProposals(
            @RequestParam(required = false) String tenantId) {

        String effectiveTenantId = securityContext.isAdmin()
                ? (tenantId != null && !tenantId.isBlank() ? tenantId.trim() : null)
                : securityContext.resolveEffectiveTenantId(tenantId);

        return ResponseEntity.ok(workflowService.listProposals(effectiveTenantId));
    }

    /**
     * Retrieves an individual workflow proposal by ID.
     */
    @GetMapping("/workflows/{proposalId}")
    public ResponseEntity<AiWorkflowProposal> getProposal(@PathVariable UUID proposalId) {
        AiWorkflowProposal proposal = workflowService.getProposal(proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found: " + proposalId));

        securityContext.assertCanAccessTenant(proposal.tenantId());
        return ResponseEntity.ok(proposal);
    }

    /**
     * Approves an AI workflow proposal, deterministically revalidating the DAG
     * and materializing it through WorkflowService into draft status.
     */
    @PostMapping("/workflows/{proposalId}/approve")
    public ResponseEntity<AiWorkflowOperationsPort.WorkflowCreatedResult> approveWorkflowProposal(
            @PathVariable UUID proposalId) {

        AiWorkflowProposal proposal = workflowService.getProposal(proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found: " + proposalId));

        securityContext.assertCanAccessTenant(proposal.tenantId());

        AiWorkflowOperationsPort.WorkflowCreatedResult result = workflowService.approveProposal(
                proposalId,
                securityContext.getUsername(),
                securityContext.getAuthorities(),
                securityContext.getTenantId().orElse(null)
        );

        return ResponseEntity.ok(result);
    }

    /**
     * Discards an unwanted AI workflow proposal.
     */
    @PostMapping("/workflows/{proposalId}/reject")
    public ResponseEntity<Map<String, String>> rejectWorkflowProposal(
            @PathVariable UUID proposalId,
            @RequestBody(required = false) Map<String, String> body) {

        AiWorkflowProposal proposal = workflowService.getProposal(proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found: " + proposalId));

        securityContext.assertCanAccessTenant(proposal.tenantId());

        String reason = body != null ? body.get("reason") : "Operator rejected proposal";
        workflowService.rejectProposal(proposalId, securityContext.getUsername(), reason);

        return ResponseEntity.ok(Map.of("status", "REJECTED", "proposalId", proposalId.toString()));
    }

    // --- AI Operations Agent Endpoints ---

    /**
     * Conducts an incident investigation across live telemetry records.
     */
    @PostMapping("/incidents/analyze")
    public ResponseEntity<IncidentAnalysisResponse> analyzeIncident(
            @Valid @RequestBody IncidentAnalysisRequest request) {

        String effectiveTenantId = securityContext.resolveEffectiveTenantId(request.tenantId());
        IncidentAnalysisResponse response = operationsService.analyzeIncident(request, effectiveTenantId);

        return ResponseEntity.ok(response);
    }

    /**
     * Lists active or past operational recommendations.
     */
    @GetMapping("/actions")
    public ResponseEntity<List<AiOperationalAction>> listActions(
            @RequestParam(required = false) AiActionStatus status,
            @RequestParam(required = false) String tenantId) {

        String effectiveTenantId = securityContext.isAdmin()
                ? (tenantId != null && !tenantId.isBlank() ? tenantId.trim() : null)
                : securityContext.resolveEffectiveTenantId(tenantId);

        return ResponseEntity.ok(operationsService.listActions(effectiveTenantId, status));
    }

    /**
     * Inspects details of a specific AI operational recommendation.
     */
    @GetMapping("/actions/{actionId}")
    public ResponseEntity<AiOperationalAction> getAction(@PathVariable UUID actionId) {
        AiOperationalAction action = operationsService.getAction(actionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Action not found: " + actionId));

        securityContext.assertCanAccessTenant(action.tenantId());
        return ResponseEntity.ok(action);
    }

    /**
     * Approves and executes an operational recommendation with STALE STATE PROTECTION.
     */
    @PostMapping("/actions/{actionId}/approve")
    public ResponseEntity<AiOperationalAction> approveAction(@PathVariable UUID actionId) {
        AiOperationalAction action = operationsService.getAction(actionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Action not found: " + actionId));

        securityContext.assertCanAccessTenant(action.tenantId());

        AiOperationalAction executed = operationsService.approveAction(
                actionId,
                securityContext.getUsername(),
                securityContext.getAuthorities(),
                securityContext.getTenantId().orElse(null)
        );

        return ResponseEntity.ok(executed);
    }

    /**
     * Rejects an operational recommendation.
     */
    @PostMapping("/actions/{actionId}/reject")
    public ResponseEntity<Map<String, String>> rejectAction(
            @PathVariable UUID actionId,
            @RequestBody(required = false) Map<String, String> body) {

        AiOperationalAction action = operationsService.getAction(actionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Action not found: " + actionId));

        securityContext.assertCanAccessTenant(action.tenantId());

        String reason = body != null ? body.get("reason") : "Operator rejected action";
        operationsService.rejectAction(actionId, securityContext.getUsername(), reason);

        return ResponseEntity.ok(Map.of("status", "REJECTED", "actionId", actionId.toString()));
    }
}
