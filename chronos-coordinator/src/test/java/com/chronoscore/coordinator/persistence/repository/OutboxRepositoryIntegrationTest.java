package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.coordinator.persistence.model.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OutboxRepositoryIntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM chronos_outbox_events");
        jdbcTemplate.execute("DELETE FROM chronos_task_audit_log");
        jdbcTemplate.execute("DELETE FROM chronos_execution_attempts");
        jdbcTemplate.execute("DELETE FROM chronos_tasks");
    }

    private OutboxEvent createEvent(UUID eventId, String aggregateId, Instant createdAt) {
        return OutboxEvent.builder()
                .eventId(eventId != null ? eventId : UUID.randomUUID())
                .aggregateType("TASK")
                .aggregateId(aggregateId != null ? aggregateId : UUID.randomUUID().toString())
                .eventType("TaskStatusChanged")
                .payload("{\"status\":\"QUEUED\"}")
                .createdAt(createdAt != null ? createdAt : Instant.now())
                .build();
    }

    @Test
    @DisplayName("append and findById: persists and retrieves outbox event")
    void testAppendAndFindById() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = createEvent(eventId, "agg-1", Instant.now());

        OutboxEvent saved = outboxRepository.append(event);
        assertThat(saved).isNotNull();

        Optional<OutboxEvent> retrieved = outboxRepository.findById(eventId);
        assertThat(retrieved).isPresent();
        OutboxEvent e = retrieved.get();
        assertThat(e.getEventId()).isEqualTo(eventId);
        assertThat(e.getAggregateType()).isEqualTo("TASK");
        assertThat(e.getAggregateId()).isEqualTo("agg-1");
        assertThat(e.getEventType()).isEqualTo("TaskStatusChanged");
        assertThat(e.getPayload()).contains("QUEUED");
        assertThat(e.getPublishedAt()).isNull();
        assertThat(e.getAttemptCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("findPendingEvents: fetches only unpublished events in creation order")
    void testFindPendingEvents() {
        Instant now = Instant.now();
        OutboxEvent e1 = outboxRepository.append(createEvent(UUID.randomUUID(), "agg-1", now.minusSeconds(20)));
        OutboxEvent e2 = outboxRepository.append(createEvent(UUID.randomUUID(), "agg-2", now.minusSeconds(10)));
        OutboxEvent e3 = outboxRepository.append(createEvent(UUID.randomUUID(), "agg-3", now));

        // Mark e2 as published
        outboxRepository.markPublished(e2.getEventId(), Instant.now());

        List<OutboxEvent> pending = outboxRepository.findPendingEvents(10);
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).getEventId()).isEqualTo(e1.getEventId());
        assertThat(pending.get(1).getEventId()).isEqualTo(e3.getEventId());
    }

    @Test
    @DisplayName("markPublished: marks event as published and updates timestamp")
    void testMarkPublished() {
        UUID eventId = UUID.randomUUID();
        outboxRepository.append(createEvent(eventId, "agg-1", Instant.now()));

        Instant publishedAt = Instant.now();
        boolean marked = outboxRepository.markPublished(eventId, publishedAt);
        assertThat(marked).isTrue();

        OutboxEvent retrieved = outboxRepository.findById(eventId).orElseThrow();
        assertThat(retrieved.isPublished()).isTrue();
        assertThat(retrieved.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("incrementAttempt: increments retry attempt counter")
    void testIncrementAttempt() {
        UUID eventId = UUID.randomUUID();
        outboxRepository.append(createEvent(eventId, "agg-1", Instant.now()));

        outboxRepository.incrementAttempt(eventId);
        outboxRepository.incrementAttempt(eventId);

        OutboxEvent retrieved = outboxRepository.findById(eventId).orElseThrow();
        assertThat(retrieved.getAttemptCount()).isEqualTo(2);
    }
}
