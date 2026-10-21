package com.chronoscore.ai.dto;

import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Validated, auditable workflow DAG proposal produced by the AI Workflow Agent.
 */
public record AiWorkflowProposal(
        UUID proposalId,
        String tenantId,
        String workflowName,
        String description,
        List<WorkflowNodeDefinition> nodes,
        List<WorkflowEdge> edges,
        String explanation,
        boolean valid,
        List<String> validationErrors,
        AiProposalStatus status,
        Instant createdAt,
        Instant expiresAt
) {
    public AiWorkflowProposal {
        nodes = nodes != null ? List.copyOf(nodes) : List.of();
        edges = edges != null ? List.copyOf(edges) : List.of();
        validationErrors = validationErrors != null ? List.copyOf(validationErrors) : List.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID proposalId = UUID.randomUUID();
        private String tenantId;
        private String workflowName;
        private String description = "";
        private List<WorkflowNodeDefinition> nodes = List.of();
        private List<WorkflowEdge> edges = List.of();
        private String explanation = "";
        private boolean valid = false;
        private List<String> validationErrors = List.of();
        private AiProposalStatus status = AiProposalStatus.PENDING_REVIEW;
        private Instant createdAt = Instant.now();
        private Instant expiresAt = Instant.now().plusSeconds(1800);

        public Builder proposalId(UUID proposalId) {
            this.proposalId = proposalId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder workflowName(String workflowName) {
            this.workflowName = workflowName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder nodes(List<WorkflowNodeDefinition> nodes) {
            this.nodes = nodes;
            return this;
        }

        public Builder edges(List<WorkflowEdge> edges) {
            this.edges = edges;
            return this;
        }

        public Builder explanation(String explanation) {
            this.explanation = explanation;
            return this;
        }

        public Builder valid(boolean valid) {
            this.valid = valid;
            return this;
        }

        public Builder validationErrors(List<String> validationErrors) {
            this.validationErrors = validationErrors;
            return this;
        }

        public Builder status(AiProposalStatus status) {
            this.status = status;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public AiWorkflowProposal build() {
            return new AiWorkflowProposal(proposalId, tenantId, workflowName, description, nodes, edges,
                    explanation, valid, validationErrors, status, createdAt, expiresAt);
        }
    }
}
