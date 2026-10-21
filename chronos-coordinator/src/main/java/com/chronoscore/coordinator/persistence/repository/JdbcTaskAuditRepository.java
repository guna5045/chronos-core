package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.exception.PersistenceException;
import com.chronoscore.coordinator.persistence.model.TaskAuditRecord;
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
import java.util.UUID;

/**
 * JDBC implementation of {@link TaskAuditRepository}.
 * <p>
 * Strictly append-only: audit rows are never updated or deleted.
 */
@Repository
public class JdbcTaskAuditRepository implements TaskAuditRepository {

    private static final String SELECT_COLUMNS =
            "audit_id, task_id, from_status, to_status, attempt_number, reason, metadata, created_at";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<TaskAuditRecord> rowMapper = new TaskAuditRowMapper();

    public JdbcTaskAuditRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public TaskAuditRecord append(TaskAuditRecord record) {
        Objects.requireNonNull(record, "record must not be null");

        String sql = "INSERT INTO chronos_task_audit_log (" +
                "audit_id, task_id, from_status, to_status, attempt_number, reason, metadata, created_at" +
                ") VALUES (" +
                ":auditId, :taskId, :fromStatus, :toStatus, :attemptNumber, :reason, :metadata, :createdAt" +
                ")";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("auditId", record.getAuditId())
                .addValue("taskId", record.getTaskId())
                .addValue("fromStatus", record.getFromStatus() != null ? record.getFromStatus().name() : null)
                .addValue("toStatus", record.getToStatus().name())
                .addValue("attemptNumber", record.getAttemptNumber())
                .addValue("reason", record.getReason())
                .addValue("metadata", record.getMetadata())
                .addValue("createdAt", Timestamp.from(record.getCreatedAt()));

        try {
            jdbcTemplate.update(sql, params);
            return record;
        } catch (Exception ex) {
            throw new PersistenceException("Failed to append audit record for task " + record.getTaskId(), ex);
        }
    }

    @Override
    public List<TaskAuditRecord> findByTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_task_audit_log " +
                "WHERE task_id = :taskId ORDER BY created_at ASC";

        MapSqlParameterSource params = new MapSqlParameterSource("taskId", taskId);
        return jdbcTemplate.query(sql, params, rowMapper);
    }

    private static class TaskAuditRowMapper implements RowMapper<TaskAuditRecord> {
        @Override
        public TaskAuditRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID auditId = getUuid(rs, "audit_id");
            UUID taskId = getUuid(rs, "task_id");
            String fromStatusStr = rs.getString("from_status");
            TaskStatus fromStatus = fromStatusStr != null ? TaskStatus.valueOf(fromStatusStr) : null;
            TaskStatus toStatus = TaskStatus.valueOf(rs.getString("to_status"));
            int attemptNumber = rs.getInt("attempt_number");
            String reason = rs.getString("reason");
            String metadata = rs.getString("metadata");
            Instant createdAt = rs.getTimestamp("created_at").toInstant();

            return TaskAuditRecord.builder()
                    .auditId(auditId)
                    .taskId(taskId)
                    .fromStatus(fromStatus)
                    .toStatus(toStatus)
                    .attemptNumber(attemptNumber)
                    .reason(reason)
                    .metadata(metadata)
                    .createdAt(createdAt)
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
