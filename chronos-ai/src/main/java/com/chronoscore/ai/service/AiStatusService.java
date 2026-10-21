package com.chronoscore.ai.service;

import com.chronoscore.ai.configuration.AiProperties;
import com.chronoscore.ai.dto.AiStatusResponse;
import com.chronoscore.ai.provider.AiProvider;

import java.util.Objects;

/**
 * Service returning high-level operational status of the AI layer.
 * Secrets such as API keys are strictly excluded.
 */
public class AiStatusService {

    private final AiProperties aiProperties;
    private final AiProvider aiProvider;

    public AiStatusService(AiProperties aiProperties, AiProvider aiProvider) {
        this.aiProperties = Objects.requireNonNull(aiProperties, "aiProperties must not be null");
        this.aiProvider = Objects.requireNonNull(aiProvider, "aiProvider must not be null");
    }

    public AiStatusResponse getStatus() {
        boolean enabled = aiProperties.isEnabled();
        String providerName = aiProvider.getProviderName();
        String modelName = aiProvider.getModelName();
        boolean available = enabled && aiProvider.isAvailable();

        return new AiStatusResponse(enabled, providerName, modelName, available);
    }
}
