package com.chronoscore.coordinator.workflow.repository;

import java.util.UUID;

/**
 * Repository interface for appending immutable audit records for workflow execution state changes.
 */
public interface WorkflowAuditRepository {

    void appendAudit(UUID executionId, String fromStatus, String toStatus, String reason, String metadata);
}
