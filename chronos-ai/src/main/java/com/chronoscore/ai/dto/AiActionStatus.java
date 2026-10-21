package com.chronoscore.ai.dto;

/**
 * Lifecycle status of an AI operational recommendation.
 */
public enum AiActionStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    EXPIRED,
    EXECUTED
}
