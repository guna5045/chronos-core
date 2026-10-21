package com.chronoscore.ai.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration properties for Chronos AI layer.
 */
@ConfigurationProperties(prefix = "chronos.ai")
public class AiProperties {

    /**
     * Master switch to enable or disable AI capabilities entirely.
     * When false, all core Chronos operations continue normally without AI.
     */
    private boolean enabled = true;

    /**
     * AI provider implementation to use ("stub" or "openai-compatible").
     */
    private String provider = "stub";

    /**
     * Base URL for OpenAI-compatible endpoint.
     */
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * Secret API key for external provider. Never logged or returned in responses.
     */
    private String apiKey = "";

    /**
     * AI model name to invoke.
     */
    private String model = "gpt-4o-mini";

    /**
     * HTTP and execution timeout for AI provider requests in seconds.
     */
    private int timeoutSeconds = 30;

    /**
     * Time-to-live for AI operational recommendations in minutes before expiration.
     */
    private int actionTtlMinutes = 30;

    /**
     * Maximum allowed character length for natural language prompts.
     */
    private int maxPromptLength = 4000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getActionTtlMinutes() {
        return actionTtlMinutes;
    }

    public void setActionTtlMinutes(int actionTtlMinutes) {
        this.actionTtlMinutes = actionTtlMinutes;
    }

    public int getMaxPromptLength() {
        return maxPromptLength;
    }

    public void setMaxPromptLength(int maxPromptLength) {
        this.maxPromptLength = maxPromptLength;
    }
}
