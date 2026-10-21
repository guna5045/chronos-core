package com.chronoscore.ai.policy;

/**
 * Thrown when an operator attempts to approve an AI action after its TTL has expired.
 */
public class AiActionExpiredException extends RuntimeException {
    public AiActionExpiredException(String message) {
        super(message);
    }
}
