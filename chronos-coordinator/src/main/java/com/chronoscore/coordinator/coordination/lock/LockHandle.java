package com.chronoscore.coordinator.coordination.lock;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable handle representing an actively acquired distributed lock in Redis.
 * <p>
 * Encapsulates the resource name, owner token, acquisition timestamp, and expiry
 * so callers cannot accidentally release or manipulate locks they do not own.
 */
public final class LockHandle {

    private final String resource;
    private final String ownerToken;
    private final Instant acquiredAt;
    private final Instant expiresAt;
    private final Duration ttl;

    public LockHandle(String resource, String ownerToken, Instant acquiredAt, Duration ttl) {
        this.resource = Objects.requireNonNull(resource, "resource must not be null");
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken must not be null");
        this.acquiredAt = Objects.requireNonNull(acquiredAt, "acquiredAt must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        this.expiresAt = acquiredAt.plus(ttl);
    }

    public LockHandle(String resource, String ownerToken, Instant acquiredAt, Instant expiresAt, Duration ttl) {
        this.resource = Objects.requireNonNull(resource, "resource must not be null");
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken must not be null");
        this.acquiredAt = Objects.requireNonNull(acquiredAt, "acquiredAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    }

    public String getResource() {
        return resource;
    }

    public String getOwnerToken() {
        return ownerToken;
    }

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Duration getTtl() {
        return ttl;
    }

    /**
     * Checks if this lock handle's local lease has expired based on current system time.
     *
     * @return true if expired.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Creates an updated LockHandle reflecting a renewed TTL.
     *
     * @param newTtl extended duration.
     * @return updated LockHandle.
     */
    public LockHandle withRenewal(Duration newTtl) {
        Instant now = Instant.now();
        return new LockHandle(resource, ownerToken, acquiredAt, now.plus(newTtl), newTtl);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LockHandle that = (LockHandle) o;
        return Objects.equals(resource, that.resource) &&
                Objects.equals(ownerToken, that.ownerToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resource, ownerToken);
    }

    @Override
    public String toString() {
        return "LockHandle{" +
                "resource='" + resource + '\'' +
                ", acquiredAt=" + acquiredAt +
                ", expiresAt=" + expiresAt +
                ", isExpired=" + isExpired() +
                '}';
    }
}
