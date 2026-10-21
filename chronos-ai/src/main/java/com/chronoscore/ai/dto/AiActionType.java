package com.chronoscore.ai.dto;

/**
 * Controlled set of actionable operational recommendations.
 * AI cannot autonomously execute any mutating action.
 */
public enum AiActionType {
    RETRY_TASK,
    CANCEL_TASK,
    REPLAY_DLQ,
    ARCHIVE_DLQ,
    CANCEL_WORKFLOW,
    INSPECT_WORKER
}
