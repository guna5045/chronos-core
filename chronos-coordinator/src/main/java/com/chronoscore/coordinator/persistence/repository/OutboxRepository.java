package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.coordinator.persistence.model.OutboxEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for the Transactional Outbox pattern.
 * <p>
 * Ensures events are safely staged in the database during state mutations,
 * ready for reliable at-least-once asynchronous publication by future publisher components.
 */
public interface OutboxRepository {

    /**
     * Appends an event to the transactional outbox table.
     *
     * @param event outbox event to append.
     * @return persisted outbox event.
     */
    OutboxEvent append(OutboxEvent event);

    /**
     * Retrieves pending (unpublished) outbox events ordered by creation timestamp ascending.
     *
     * @param limit maximum number of events to fetch.
     * @return list of pending outbox events.
     */
    List<OutboxEvent> findPendingEvents(int limit);

    /**
     * Marks an outbox event as successfully published.
     *
     * @param eventId     event UUID.
     * @param publishedAt publication timestamp.
     * @return true if marked, false if event not found.
     */
    boolean markPublished(UUID eventId, Instant publishedAt);

    /**
     * Increments the delivery attempt counter for a pending event after a publication retry.
     *
     * @param eventId event UUID.
     * @return true if updated, false if event not found.
     */
    boolean incrementAttempt(UUID eventId);

    /**
     * Finds an outbox event by ID.
     *
     * @param eventId event UUID.
     * @return Optional containing the event if found, or empty.
     */
    Optional<OutboxEvent> findById(UUID eventId);
}
