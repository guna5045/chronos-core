package com.chronoscore.coordinator.api.dto.response;

import java.util.List;

/**
 * Detailed REST response containing workflow execution metadata and its constituent node states.
 */
public record WorkflowExecutionDetailsResponse(
        WorkflowExecutionResponse execution,
        List<WorkflowNodeResponse> nodes
) {
}
