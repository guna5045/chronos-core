package com.chronoscore.ai.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Diagnostic analysis report produced by the AI Operations Agent.
 */
public record IncidentAnalysisResponse(
        UUID analysisId,
        IncidentScope scope,
        String targetId,
        String summary,
        AiIncidentSeverity severity,
        List<AiEvidenceRecord> evidence,
        List<String> likelyCauses,
        List<AiOperationalAction> recommendations,
        Instant analyzedAt
) {
    public IncidentAnalysisResponse {
        evidence = evidence != null ? List.copyOf(evidence) : List.of();
        likelyCauses = likelyCauses != null ? List.copyOf(likelyCauses) : List.of();
        recommendations = recommendations != null ? List.copyOf(recommendations) : List.of();
    }
}
