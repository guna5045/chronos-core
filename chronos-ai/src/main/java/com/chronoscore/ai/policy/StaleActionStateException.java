package com.chronoscore.ai.policy;

/**
 * Thrown when an AI recommendation is rejected because the target system entity's state
 * has changed since the recommendation was generated.
 */
public class StaleActionStateException extends RuntimeException {
    public StaleActionStateException(String message) {
        super(message);
    }
}
