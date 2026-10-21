package com.chronoscore.coordinator.api.dto.request;

/**
 * Request payload for triggering an explicit task retry via REST.
 */
public record TaskRetryRequest(
        String reason
) {
}
