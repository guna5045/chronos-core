package com.chronoscore.ai.provider;

/**
 * Pluggable abstraction for AI language model providers.
 * <p>
 * Ensures Chronos Core is never hardwired to a single external vendor.
 */
public interface AiProvider {

    /**
     * @return unique identifier of the provider (e.g. "openai-compatible", "stub").
     */
    String getProviderName();

    /**
     * @return configured model identifier (e.g. "gpt-4o-mini", "stub-model").
     */
    String getModelName();

    /**
     * @return true if the provider is currently reachable and operational.
     */
    boolean isAvailable();

    /**
     * Generates a structured completion for the given request.
     *
     * @param request completion parameters including system and user prompts.
     * @return structured completion response.
     * @throws AiProviderException on communication failure, timeout, or provider error.
     */
    AiCompletionResponse generate(AiCompletionRequest request);
}
