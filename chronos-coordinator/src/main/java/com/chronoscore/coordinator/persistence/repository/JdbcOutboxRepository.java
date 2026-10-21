package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.coordinator.persistence.exception.PersistenceException;
import com.chronoscore.coordinator.persistence.model.OutboxEvent;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed implementation of {@link OutboxRepository}.
 */
@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private static final String SELECT_COLUMNS =
            "event_id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at, attempt_count";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<OutboxEvent> rowMapper = new OutboxEventRowMapper();

    public JdbcOutboxRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public OutboxEvent append(OutboxEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        String sql = "INSERT INTO chronos_outbox_events (" +
                "event_id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at, attempt_count" +
                ") VALUES (" +
                ":eventId, :aggregateType, :aggregateId, :eventType, :payload, :createdAt, :publishedAt, :attemptCount" +
                ")";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventId", event.getEventId())
                .addValue("aggregateType", event.getAggregateType())
                .addValue("aggregateId", event.getAggregateId())
                .addValue("eventType", event.getEventType())
                .addValue("payload", event.getPayload())
                .addValue("createdAt", Timestamp.from(event.getCreatedAt()))
                .addValue("publishedAt", event.getPublishedAt() != null ? Timestamp.from(event.getPublishedAt()) : null)
                .addValue("attemptCount", event.getAttemptCount());

        try {
            jdbcTemplate.update(sql, params);
            return event;
        } catch (Exception ex) {
            throw new PersistenceException("Failed to append outbox event " + event.getEventId(), ex);
        }
    }

    @Override
    public List<OutboxEvent> findPendingEvents(int limit) {
        int maxLimit = Math.max(1, limit);
        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_outbox_events " +
                "WHERE published_at IS NULL " +
                "ORDER BY created_at ASC " +
                "LIMIT :limit";

        MapSqlParameterSource params = new MapSqlParameterSource("limit", maxLimit);
        return jdbcTemplate.query(sql, params, rowMapper);
    }

    @Override
    public boolean markPublished(UUID eventId, Instant publishedAt) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Instant pubTime = publishedAt != null ? publishedAt : Instant.now();

        String sql = "UPDATE chronos_outbox_events SET published_at = :publishedAt WHERE event_id = :eventId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("publishedAt", Timestamp.from(pubTime));

        return jdbcTemplate.update(sql, params) > 0;
    }

    @Override
    public boolean incrementAttempt(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");

        String sql = "UPDATE chronos_outbox_events SET attempt_count = attempt_count + 1 WHERE event_id = :eventId";
        MapSqlParameterSource params = new MapSqlParameterSource("eventId", eventId);

        return jdbcTemplate.update(sql, params) > 0;
    }

    @Override
    public Optional<OutboxEvent> findById(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");

        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_outbox_events WHERE event_id = :eventId";
        MapSqlParameterSource params = new MapSqlParameterSource("eventId", eventId);
        List<OutboxEvent> results = jdbcTemplate.query(sql, params, rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private static class OutboxEventRowMapper implements RowMapper<OutboxEvent> {
        @Override
        public OutboxEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID eventId = getUuid(rs, "event_id");
            String aggregateType = rs.getString("aggregate_type");
            String aggregateId = rs.getString("aggregate_id");
            String eventType = rs.getString("event_type");
            String payload = rs.getString("payload");
            Instant createdAt = rs.getTimestamp("created_at").toInstant();
            Timestamp publishedAtTs = rs.getTimestamp("published_at");
            Instant publishedAt = publishedAtTs != null ? publishedAtTs.toInstant() : null;
            int attemptCount = rs.getInt("attempt_count");

            return OutboxEvent.builder()
                    .eventId(eventId)
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payload)
                    .createdAt(createdAt)
                    .publishedAt(publishedAt)
                    .attemptCount(attemptCount)
                    .build();
        }

        private UUID getUuid(ResultSet rs, String col) throws SQLException {
            Object obj = rs.getObject(col);
            if (obj == null) return null;
            if (obj instanceof UUID uuid) return uuid;
            return UUID.fromString(obj.toString());
        }
    }
}
