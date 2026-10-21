package com.chronoscore.ai.provider;

import java.util.Objects;

/**
 * Structured request sent to an AI provider.
 */
public record AiCompletionRequest(
        String systemPrompt,
        String userPrompt,
        String responseSchema,
        double temperature,
        int maxTokens
) {
    public AiCompletionRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        Objects.requireNonNull(userPrompt, "userPrompt must not be null");
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String systemPrompt = "";
        private String userPrompt = "";
        private String responseSchema = "";
        private double temperature = 0.1; // Low temperature for deterministic output
        private int maxTokens = 2048;

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder userPrompt(String userPrompt) {
            this.userPrompt = userPrompt;
            return this;
        }

        public Builder responseSchema(String responseSchema) {
            this.responseSchema = responseSchema;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public AiCompletionRequest build() {
            return new AiCompletionRequest(systemPrompt, userPrompt, responseSchema, temperature, maxTokens);
        }
    }
}
