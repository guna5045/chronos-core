package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.common.domain.ExecutionAttempt;
import com.chronoscore.common.enums.ExecutionAttemptStatus;
import com.chronoscore.coordinator.persistence.exception.PersistenceException;
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
 * JDBC-backed implementation of {@link ExecutionAttemptRepository}.
 */
@Repository
public class JdbcExecutionAttemptRepository implements ExecutionAttemptRepository {

    private static final String SELECT_COLUMNS =
            "attempt_id, task_id, worker_id, attempt_number, status, started_at, completed_at, error_code, error_message";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<ExecutionAttempt> rowMapper = new ExecutionAttemptRowMapper();

    public JdbcExecutionAttemptRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public ExecutionAttempt save(ExecutionAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");

        String sql = "INSERT INTO chronos_execution_attempts (" +
                "attempt_id, task_id, worker_id, attempt_number, status, started_at, completed_at, error_code, error_message" +
                ") VALUES (" +
                ":attemptId, :taskId, :workerId, :attemptNumber, :status, :startedAt, :completedAt, :errorCode, :errorMessage" +
                ")";

        MapSqlParameterSource params = toParameterSource(attempt);

        try {
            jdbcTemplate.update(sql, params);
            return attempt;
        } catch (Exception ex) {
            throw new PersistenceException("Failed to persist execution attempt " + attempt.getAttemptId(), ex);
        }
    }

    @Override
    public ExecutionAttempt update(ExecutionAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");

        String sql = "UPDATE chronos_execution_attempts SET " +
                "worker_id = :workerId, " +
                "status = :status, " +
                "completed_at = :completedAt, " +
                "error_code = :errorCode, " +
                "error_message = :errorMessage " +
                "WHERE attempt_id = :attemptId";

        MapSqlParameterSource params = toParameterSource(attempt);

        try {
            int rows = jdbcTemplate.update(sql, params);
            if (rows == 0) {
                throw new PersistenceException("Attempt not found for update: " + attempt.getAttemptId());
            }
            return attempt;
        } catch (Exception ex) {
            throw new PersistenceException("Failed to update execution attempt " + attempt.getAttemptId(), ex);
        }
    }

    @Override
    public Optional<ExecutionAttempt> findById(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_execution_attempts WHERE attempt_id = :attemptId";
        MapSqlParameterSource params = new MapSqlParameterSource("attemptId", attemptId);
        List<ExecutionAttempt> results = jdbcTemplate.query(sql, params, rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<ExecutionAttempt> findByTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_execution_attempts " +
                "WHERE task_id = :taskId ORDER BY attempt_number ASC";
        MapSqlParameterSource params = new MapSqlParameterSource("taskId", taskId);
        return jdbcTemplate.query(sql, params, rowMapper);
    }

    @Override
    public Optional<ExecutionAttempt> findLatestAttempt(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_execution_attempts " +
                "WHERE task_id = :taskId ORDER BY attempt_number DESC LIMIT 1";
        MapSqlParameterSource params = new MapSqlParameterSource("taskId", taskId);
        List<ExecutionAttempt> results = jdbcTemplate.query(sql, params, rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<ExecutionAttempt> findByTaskIdAndAttemptNumber(UUID taskId, int attemptNumber) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_execution_attempts " +
                "WHERE task_id = :taskId AND attempt_number = :attemptNumber";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("attemptNumber", attemptNumber);

        List<ExecutionAttempt> results = jdbcTemplate.query(sql, params, rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private MapSqlParameterSource toParameterSource(ExecutionAttempt attempt) {
        return new MapSqlParameterSource()
                .addValue("attemptId", attempt.getAttemptId())
                .addValue("taskId", attempt.getTaskId())
                .addValue("workerId", attempt.getWorkerId())
                .addValue("attemptNumber", attempt.getAttemptNumber())
                .addValue("status", attempt.getStatus().name())
                .addValue("startedAt", Timestamp.from(attempt.getStartedAt()))
                .addValue("completedAt", attempt.getCompletedAt() != null ? Timestamp.from(attempt.getCompletedAt()) : null)
                .addValue("errorCode", attempt.getErrorCode())
                .addValue("errorMessage", attempt.getErrorMessage());
    }

    private static class ExecutionAttemptRowMapper implements RowMapper<ExecutionAttempt> {
        @Override
        public ExecutionAttempt mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID attemptId = getUuid(rs, "attempt_id");
            UUID taskId = getUuid(rs, "task_id");
            String workerId = rs.getString("worker_id");
            int attemptNumber = rs.getInt("attempt_number");
            ExecutionAttemptStatus status = ExecutionAttemptStatus.valueOf(rs.getString("status"));
            Instant startedAt = getInstant(rs, "started_at");
            Instant completedAt = getInstant(rs, "completed_at");
            String errorCode = rs.getString("error_code");
            String errorMessage = rs.getString("error_message");

            return ExecutionAttempt.builder()
                    .attemptId(attemptId)
                    .taskId(taskId)
                    .workerId(workerId)
                    .attemptNumber(attemptNumber)
                    .status(status)
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .errorCode(errorCode)
                    .errorMessage(errorMessage)
                    .build();
        }

        private UUID getUuid(ResultSet rs, String col) throws SQLException {
            Object obj = rs.getObject(col);
            if (obj == null) return null;
            if (obj instanceof UUID uuid) return uuid;
            return UUID.fromString(obj.toString());
        }

        private Instant getInstant(ResultSet rs, String col) throws SQLException {
            Timestamp ts = rs.getTimestamp(col);
            return ts != null ? ts.toInstant() : null;
        }
    }
}
