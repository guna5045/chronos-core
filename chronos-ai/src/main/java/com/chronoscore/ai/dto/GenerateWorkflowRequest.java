package com.chronoscore.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Natural language request to generate a structured workflow proposal.
 */
public record GenerateWorkflowRequest(
        @NotBlank(message = "prompt must not be blank")
        @Size(max = 4000, message = "prompt must not exceed 4000 characters")
        String prompt,

        @Size(max = 128, message = "workflowName must not exceed 128 characters")
        String workflowName,

        String tenantId
) {}
