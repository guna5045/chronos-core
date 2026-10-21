package com.chronoscore.ai.dto;

/**
 * Deterministic bounded set of incident severity levels.
 * Arbitrary strings are strictly prohibited.
 */
public enum AiIncidentSeverity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
