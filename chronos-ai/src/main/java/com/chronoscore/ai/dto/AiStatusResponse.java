package com.chronoscore.ai.dto;

/**
 * High-level operational status of the Chronos AI layer.
 * Secrets such as API keys are strictly excluded.
 */
public record AiStatusResponse(
        boolean enabled,
        String provider,
        String model,
        boolean available
) {}
