package com.chronoscore.coordinator.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for registering a new workflow definition.
 */
public record CreateWorkflowRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 128, message = "name cannot exceed 128 characters")
        String name,

        @Size(max = 1024, message = "description cannot exceed 1024 characters")
        String description
) {
}
