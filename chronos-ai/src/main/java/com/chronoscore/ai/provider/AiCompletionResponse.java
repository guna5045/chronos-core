package com.chronoscore.ai.provider;

import java.time.Duration;
import java.util.Objects;

/**
 * Structured response received from an AI provider.
 */
public record AiCompletionResponse(
        String content,
        String model,
        int promptTokens,
        int completionTokens,
        Duration duration
) {
    public AiCompletionResponse {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(model, "model must not be null");
        if (duration == null) {
            duration = Duration.ZERO;
        }
    }
}
