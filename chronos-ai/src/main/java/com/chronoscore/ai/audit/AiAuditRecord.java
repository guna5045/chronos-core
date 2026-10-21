package com.chronoscore.ai.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit record capturing AI operations, recommendations, and human approvals.
 */
public record AiAuditRecord(
        UUID auditId,
        String requestId,
        String agent,
        String principal,
        String tenantId,
        String action,
        String targetId,
        boolean success,
        String details,
        Instant timestamp
) {}
