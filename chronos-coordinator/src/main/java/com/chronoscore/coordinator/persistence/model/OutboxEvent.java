package com.chronoscore.coordinator.persistence.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entity representing a durable event stored in the transactional outbox table.
 * <p>
 * Written in the exact same database transaction as domain mutations to guarantee
 * at-least-once event delivery semantics without dual-write inconsistency.
 */
public final class OutboxEvent {

    private final UUID eventId;
    private final String aggregateType;
    private final String aggregateId;
    private final String eventType;
    private final String payload;
    private final Instant createdAt;
    private final Instant publishedAt;
    private final int attemptCount;

    private OutboxEvent(Builder builder) {
        this.eventId = builder.eventId != null ? builder.eventId : UUID.randomUUID();
        this.aggregateType = Objects.requireNonNull(builder.aggregateType, "aggregateType must not be null");
        this.aggregateId = Objects.requireNonNull(builder.aggregateId, "aggregateId must not be null");
        this.eventType = Objects.requireNonNull(builder.eventType, "eventType must not be null");
        this.payload = Objects.requireNonNull(builder.payload, "payload must not be null");
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.publishedAt = builder.publishedAt;
        this.attemptCount = Math.max(builder.attemptCount, 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public Builder toBuilder() {
        return new Builder()
                .eventId(this.eventId)
                .aggregateType(this.aggregateType)
                .aggregateId(this.aggregateId)
                .eventType(this.eventType)
                .payload(this.payload)
                .createdAt(this.createdAt)
                .publishedAt(this.publishedAt)
                .attemptCount(this.attemptCount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OutboxEvent that = (OutboxEvent) o;
        return Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }

    @Override
    public String toString() {
        return "OutboxEvent{" +
                "eventId=" + eventId +
                ", aggregateType='" + aggregateType + '\'' +
                ", aggregateId='" + aggregateId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", createdAt=" + createdAt +
                ", publishedAt=" + publishedAt +
                ", attemptCount=" + attemptCount +
                '}';
    }

    public static final class Builder {
        private UUID eventId;
        private String aggregateType;
        private String aggregateId;
        private String eventType;
        private String payload;
        private Instant createdAt;
        private Instant publishedAt;
        private int attemptCount = 0;

        private Builder() {
        }

        public Builder eventId(UUID eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder aggregateType(String aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        public Builder aggregateId(String aggregateId) {
            this.aggregateId = aggregateId;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder publishedAt(Instant publishedAt) {
            this.publishedAt = publishedAt;
            return this;
        }

        public Builder attemptCount(int attemptCount) {
            this.attemptCount = attemptCount;
            return this;
        }

        public OutboxEvent build() {
            return new OutboxEvent(this);
        }
    }
}
