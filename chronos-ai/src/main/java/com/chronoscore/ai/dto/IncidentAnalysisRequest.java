package com.chronoscore.ai.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request payload to initiate an incident diagnosis.
 */
public record IncidentAnalysisRequest(
        @NotNull(message = "scope must not be null")
        IncidentScope scope,

        String targetId,

        String tenantId
) {}
