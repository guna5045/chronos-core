package com.chronoscore.common.dto;

import com.chronoscore.common.enums.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Data Transfer Object representing a state transition event emitted by the coordinator.
 */
public record TaskStateChangeDTO(
        UUID taskId,
        TaskStatus previousStatus,
        TaskStatus newStatus,
        int currentAttempt,
        Instant timestamp,
        String reason
) {
}
