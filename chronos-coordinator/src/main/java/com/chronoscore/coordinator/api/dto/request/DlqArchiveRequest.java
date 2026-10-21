package com.chronoscore.coordinator.api.dto.request;

/**
 * Request payload for archiving/discarding a task in DLQ.
 */
public record DlqArchiveRequest(
        String reason
) {
}
