package com.chronoscore.coordinator.persistence.exception;

/**
 * Thrown when a duplicate task submission is detected, such as a violation
 * of the (tenant_id, idempotency_key) unique constraint.
 */
public class DuplicateTaskException extends RuntimeException {

    private final String tenantId;
    private final String idempotencyKey;

    public DuplicateTaskException(String message, Throwable cause) {
        super(message, cause);
        this.tenantId = null;
        this.idempotencyKey = null;
    }

    public DuplicateTaskException(String tenantId, String idempotencyKey) {
        super(String.format("Duplicate task submission detected for tenant '%s' with idempotency key '%s'",
                tenantId, idempotencyKey));
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
