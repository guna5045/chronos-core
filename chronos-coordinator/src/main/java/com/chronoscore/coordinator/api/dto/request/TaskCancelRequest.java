package com.chronoscore.coordinator.api.dto.request;

/**
 * Request payload for cancelling an active or queued task via REST.
 */
public record TaskCancelRequest(
        String reason
) {
}
