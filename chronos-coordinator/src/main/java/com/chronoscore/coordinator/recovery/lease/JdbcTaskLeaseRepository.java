package com.chronoscore.coordinator.recovery.lease;

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
 * JDBC-backed implementation of {@link TaskLeaseRepository}.
 */
@Repository
public class JdbcTaskLeaseRepository implements TaskLeaseRepository {

    private static final String SELECT_COLUMNS =
            "task_id, attempt_id, worker_id, attempt_number, lease_acquired_at, lease_expires_at, leader_epoch, version";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<TaskExecutionLease> rowMapper = new TaskExecutionLeaseRowMapper();

    public JdbcTaskLeaseRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public TaskExecutionLease save(TaskExecutionLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");

        String deleteSql = "DELETE FROM chronos_task_leases WHERE task_id = :taskId";
        String insertSql = "INSERT INTO chronos_task_leases (" +
                "task_id, attempt_id, worker_id, attempt_number, lease_acquired_at, lease_expires_at, leader_epoch, version" +
                ") VALUES (" +
                ":taskId, :attemptId, :workerId, :attemptNumber, :leaseAcquiredAt, :leaseExpiresAt, :leaderEpoch, :version" +
                ")";

        MapSqlParameterSource params = toParameterSource(lease);

        try {
            jdbcTemplate.update(deleteSql, params);
            jdbcTemplate.update(insertSql, params);
            return lease;
        } catch (Exception ex) {
            throw new PersistenceException("Failed to persist task execution lease for task " + lease.getTaskId(), ex);
        }
    }

    @Override
    public boolean renewLease(UUID taskId, Instant newExpiresAt) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(newExpiresAt, "newExpiresAt must not be null");

        String sql = "UPDATE chronos_task_leases SET " +
                "lease_expires_at = :newExpiresAt, " +
                "version = version + 1 " +
                "WHERE task_id = :taskId";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("newExpiresAt", Timestamp.from(newExpiresAt));

        int rows = jdbcTemplate.update(sql, params);
        return rows > 0;
    }

    @Override
    public boolean releaseLease(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        String sql = "DELETE FROM chronos_task_leases WHERE task_id = :taskId";
        MapSqlParameterSource params = new MapSqlParameterSource("taskId", taskId);

        int rows = jdbcTemplate.update(sql, params);
        return rows > 0;
    }

    @Override
    public Optional<TaskExecutionLease> findByTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_task_leases WHERE task_id = :taskId";
        MapSqlParameterSource params = new MapSqlParameterSource("taskId", taskId);

        List<TaskExecutionLease> results = jdbcTemplate.query(sql, params, rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<TaskExecutionLease> findByWorkerId(String workerId) {
        Objects.requireNonNull(workerId, "workerId must not be null");

        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_task_leases " +
                "WHERE worker_id = :workerId ORDER BY lease_expires_at ASC";
        MapSqlParameterSource params = new MapSqlParameterSource("workerId", workerId);

        return jdbcTemplate.query(sql, params, rowMapper);
    }

    @Override
    public List<TaskExecutionLease> findExpiredLeases(Instant asOf, int limit) {
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();
        int maxLimit = Math.max(1, limit);

        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_task_leases " +
                "WHERE lease_expires_at <= :asOf ORDER BY lease_expires_at ASC LIMIT :limit";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("asOf", Timestamp.from(effectiveAsOf))
                .addValue("limit", maxLimit);

        return jdbcTemplate.query(sql, params, rowMapper);
    }

    private MapSqlParameterSource toParameterSource(TaskExecutionLease lease) {
        return new MapSqlParameterSource()
                .addValue("taskId", lease.getTaskId())
                .addValue("attemptId", lease.getAttemptId())
                .addValue("workerId", lease.getWorkerId())
                .addValue("attemptNumber", lease.getAttemptNumber())
                .addValue("leaseAcquiredAt", Timestamp.from(lease.getLeaseAcquiredAt()))
                .addValue("leaseExpiresAt", Timestamp.from(lease.getLeaseExpiresAt()))
                .addValue("leaderEpoch", lease.getLeaderEpoch())
                .addValue("version", lease.getVersion());
    }

    private static class TaskExecutionLeaseRowMapper implements RowMapper<TaskExecutionLease> {
        @Override
        public TaskExecutionLease mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID taskId = getUuid(rs, "task_id");
            UUID attemptId = getUuid(rs, "attempt_id");
            String workerId = rs.getString("worker_id");
            int attemptNumber = rs.getInt("attempt_number");
            Instant leaseAcquiredAt = getInstant(rs, "lease_acquired_at");
            Instant leaseExpiresAt = getInstant(rs, "lease_expires_at");
            long leaderEpoch = rs.getLong("leader_epoch");
            long version = rs.getLong("version");

            return TaskExecutionLease.builder()
                    .taskId(taskId)
                    .attemptId(attemptId)
                    .workerId(workerId)
                    .attemptNumber(attemptNumber)
                    .leaseAcquiredAt(leaseAcquiredAt)
                    .leaseExpiresAt(leaseExpiresAt)
                    .leaderEpoch(leaderEpoch)
                    .version(version)
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
