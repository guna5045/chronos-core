package com.chronoscore.ai.service;

import com.chronoscore.ai.agent.workflow.AiWorkflowAgent;
import com.chronoscore.ai.configuration.AiProperties;
import com.chronoscore.ai.dto.AiWorkflowProposal;
import com.chronoscore.ai.dto.GenerateWorkflowRequest;
import com.chronoscore.ai.policy.AiActionApprovalService;
import com.chronoscore.ai.ports.AiWorkflowOperationsPort;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * High-level application service for AI workflow generation, proposal inspection, and human approval.
 */
public class AiWorkflowService {

    private final AiWorkflowAgent workflowAgent;
    private final AiActionApprovalService approvalService;
    private final AiProperties aiProperties;

    public AiWorkflowService(AiWorkflowAgent workflowAgent,
                             AiActionApprovalService approvalService,
                             AiProperties aiProperties) {
        this.workflowAgent = Objects.requireNonNull(workflowAgent, "workflowAgent must not be null");
        this.approvalService = Objects.requireNonNull(approvalService, "approvalService must not be null");
        this.aiProperties = Objects.requireNonNull(aiProperties, "aiProperties must not be null");
    }

    public AiWorkflowProposal generateProposal(GenerateWorkflowRequest request, String authenticatedTenantId) {
        assertAiEnabled();
        AiWorkflowProposal proposal = workflowAgent.generateProposal(request, authenticatedTenantId);
        approvalService.registerProposal(proposal);
        return proposal;
    }

    public Optional<AiWorkflowProposal> getProposal(UUID proposalId) {
        return approvalService.getProposal(proposalId);
    }

    public List<AiWorkflowProposal> listProposals(String authenticatedTenantId) {
        return approvalService.listProposals(authenticatedTenantId);
    }

    public AiWorkflowOperationsPort.WorkflowCreatedResult approveProposal(UUID proposalId, String principal,
                                                                          Set<String> roles, String authenticatedTenantId) {
        assertAiEnabled();
        return approvalService.approveWorkflow(proposalId, principal, roles, authenticatedTenantId);
    }

    public void rejectProposal(UUID proposalId, String principal, String reason) {
        approvalService.rejectWorkflow(proposalId, principal, reason);
    }

    private void assertAiEnabled() {
        if (!aiProperties.isEnabled()) {
            throw new IllegalStateException("Chronos AI capabilities are currently disabled in this environment");
        }
    }
}
