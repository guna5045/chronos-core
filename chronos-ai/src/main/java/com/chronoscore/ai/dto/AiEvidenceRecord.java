package com.chronoscore.ai.dto;

/**
 * Concrete, verified operational telemetry record supporting an AI finding.
 * The AI cannot hallucinate facts; every finding must link to retrieved evidence.
 */
public record AiEvidenceRecord(
        String source,      // e.g. "worker", "task", "dlq", "cluster"
        String identifier,  // e.g. workerId, taskId, executionId
        String summary,     // e.g. "Failed with TIMEOUT after 3 attempts"
        String status       // e.g. "FAILED", "SUSPECTED", "DLQ"
) {}
