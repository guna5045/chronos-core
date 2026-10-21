package com.chronoscore.coordinator.api.dto.request;

import java.util.UUID;

/**
 * Request payload for executing a workflow instance.
 */
public record ExecuteWorkflowRequest(
        Integer version,
        UUID versionId,
        String tenantId
) {
}
