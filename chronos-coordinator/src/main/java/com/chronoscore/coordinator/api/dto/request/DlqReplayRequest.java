package com.chronoscore.coordinator.api.dto.request;

/**
 * Request payload for replaying a task from DLQ.
 */
public record DlqReplayRequest(
        String reason
) {
}
