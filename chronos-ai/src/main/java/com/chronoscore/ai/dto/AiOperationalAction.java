package com.chronoscore.ai.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Controlled, bounded operational recommendation requiring human approval.
 */
public record AiOperationalAction(
        UUID actionId,
        String tenantId,
        String title,
        String reason,
        AiActionRisk risk,
        AiActionType actionType,
        String targetId,
        List<AiEvidenceRecord> evidence,
        AiActionStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant executedAt,
        String executedBy,
        String executionResult
) {
    public AiOperationalAction {
        evidence = evidence != null ? List.copyOf(evidence) : List.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID actionId = UUID.randomUUID();
        private String tenantId;
        private String title;
        private String reason;
        private AiActionRisk risk = AiActionRisk.LOW;
        private AiActionType actionType;
        private String targetId;
        private List<AiEvidenceRecord> evidence = List.of();
        private AiActionStatus status = AiActionStatus.PENDING_APPROVAL;
        private Instant createdAt = Instant.now();
        private Instant expiresAt = Instant.now().plusSeconds(1800);
        private Instant executedAt;
        private String executedBy;
        private String executionResult;

        public Builder actionId(UUID actionId) {
            this.actionId = actionId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder risk(AiActionRisk risk) {
            this.risk = risk;
            return this;
        }

        public Builder actionType(AiActionType actionType) {
            this.actionType = actionType;
            return this;
        }

        public Builder targetId(String targetId) {
            this.targetId = targetId;
            return this;
        }

        public Builder evidence(List<AiEvidenceRecord> evidence) {
            this.evidence = evidence;
            return this;
        }

        public Builder status(AiActionStatus status) {
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

        public Builder executedAt(Instant executedAt) {
            this.executedAt = executedAt;
            return this;
        }

        public Builder executedBy(String executedBy) {
            this.executedBy = executedBy;
            return this;
        }

        public Builder executionResult(String executionResult) {
            this.executionResult = executionResult;
            return this;
        }

        public AiOperationalAction build() {
            return new AiOperationalAction(actionId, tenantId, title, reason, risk, actionType, targetId,
                    evidence, status, createdAt, expiresAt, executedAt, executedBy, executionResult);
        }
    }
}
