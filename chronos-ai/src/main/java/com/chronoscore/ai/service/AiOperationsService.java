package com.chronoscore.ai.service;

import com.chronoscore.ai.agent.operations.AiOperationsAgent;
import com.chronoscore.ai.configuration.AiProperties;
import com.chronoscore.ai.dto.AiActionStatus;
import com.chronoscore.ai.dto.AiOperationalAction;
import com.chronoscore.ai.dto.IncidentAnalysisRequest;
import com.chronoscore.ai.dto.IncidentAnalysisResponse;
import com.chronoscore.ai.policy.AiActionApprovalService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * High-level application service for incident analysis, operational recommendations, and human approvals.
 */
public class AiOperationsService {

    private final AiOperationsAgent operationsAgent;
    private final AiActionApprovalService approvalService;
    private final AiProperties aiProperties;

    public AiOperationsService(AiOperationsAgent operationsAgent,
                               AiActionApprovalService approvalService,
                               AiProperties aiProperties) {
        this.operationsAgent = Objects.requireNonNull(operationsAgent, "operationsAgent must not be null");
        this.approvalService = Objects.requireNonNull(approvalService, "approvalService must not be null");
        this.aiProperties = Objects.requireNonNull(aiProperties, "aiProperties must not be null");
    }

    public IncidentAnalysisResponse analyzeIncident(IncidentAnalysisRequest request, String authenticatedTenantId) {
        assertAiEnabled();
        IncidentAnalysisResponse response = operationsAgent.analyzeIncident(request, authenticatedTenantId);
        // Register proposed actions into approval service
        for (AiOperationalAction action : response.recommendations()) {
            approvalService.registerAction(action);
        }
        return response;
    }

    public List<AiOperationalAction> listActions(String authenticatedTenantId, AiActionStatus status) {
        return approvalService.listActions(authenticatedTenantId, status);
    }

    public Optional<AiOperationalAction> getAction(UUID actionId) {
        return approvalService.getAction(actionId);
    }

    public AiOperationalAction approveAction(UUID actionId, String principal, Set<String> roles, String authenticatedTenantId) {
        assertAiEnabled();
        return approvalService.approveAction(actionId, principal, roles, authenticatedTenantId);
    }

    public void rejectAction(UUID actionId, String principal, String reason) {
        approvalService.rejectAction(actionId, principal, reason);
    }

    private void assertAiEnabled() {
        if (!aiProperties.isEnabled()) {
            throw new IllegalStateException("Chronos AI capabilities are currently disabled in this environment");
        }
    }
}
