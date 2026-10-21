package com.chronoscore.ai.provider;

/**
 * Exception thrown when an AI provider fails, times out, or returns invalid responses.
 */
public class AiProviderException extends RuntimeException {

    private final String providerName;
    private final int statusCode;

    public AiProviderException(String providerName, String message) {
        super(String.format("[%s] %s", providerName, message));
        this.providerName = providerName;
        this.statusCode = 500;
    }

    public AiProviderException(String providerName, String message, Throwable cause) {
        super(String.format("[%s] %s", providerName, message), cause);
        this.providerName = providerName;
        this.statusCode = 500;
    }

    public AiProviderException(String providerName, int statusCode, String message) {
        super(String.format("[%s] HTTP %d: %s", providerName, statusCode, message));
        this.providerName = providerName;
        this.statusCode = statusCode;
    }

    public String getProviderName() {
        return providerName;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
