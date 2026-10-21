package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.coordinator.persistence.exception.DuplicateTaskException;
import com.chronoscore.coordinator.persistence.exception.OptimisticLockingException;
import com.chronoscore.coordinator.persistence.exception.PersistenceException;
import com.chronoscore.coordinator.persistence.exception.TaskNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed implementation of {@link TaskRepository} utilizing explicit SQL queries.
 * <p>
 * Key Features:
 * <ul>
 *   <li>Explicit row mapping without ORM overhead.</li>
 *   <li>Optimistic concurrency version checks on update.</li>
 *   <li>Translation of unique constraint violations to {@link DuplicateTaskException}.</li>
 *   <li>Support for pessimistic locking via {@code FOR UPDATE}.</li>
 * </ul>
 */
@Repository
public class JdbcTaskRepository implements TaskRepository {

    private static final String SELECT_COLUMNS =
            "task_id, task_type, payload, priority, status, tenant_id, " +
            "idempotency_key, concurrency_key, created_at, scheduled_at, deadline, timeout_ms, " +
            "max_attempts, current_attempt, version";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<Task> taskRowMapper = new TaskRowMapper();

    public JdbcTaskRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public Task save(Task task) {
        Objects.requireNonNull(task, "task must not be null");

        String sql = "INSERT INTO chronos_tasks (" +
                "task_id, task_type, payload, priority, status, tenant_id, " +
                "idempotency_key, concurrency_key, created_at, scheduled_at, deadline, timeout_ms, " +
                "max_attempts, current_attempt, version" +
                ") VALUES (" +
                ":taskId, :taskType, :payload, :priority, :status, :tenantId, " +
                ":idempotencyKey, :concurrencyKey, :createdAt, :scheduledAt, :deadline, :timeoutMs, " +
                ":maxAttempts, :currentAttempt, :version" +
                ")";

        MapSqlParameterSource params = toParameterSource(task);

        try {
            jdbcTemplate.update(sql, params);
            return task;
        } catch (DuplicateKeyException ex) {
            if (task.getIdempotencyKey() != null) {
                throw new DuplicateTaskException(task.getTenantId(), task.getIdempotencyKey());
            }
            throw new DuplicateTaskException("Duplicate task detected with ID: " + task.getTaskId(), ex);
        } catch (Exception ex) {
            throw new PersistenceException("Failed to persist task " + task.getTaskId(), ex);
        }
    }

    @Override
    public Optional<Task> findById(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_tasks WHERE task_id = :taskId";
        MapSqlParameterSource params = new MapSqlParameterSource("taskId", taskId);
        List<Task> results = jdbcTemplate.query(sql, params, taskRowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<Task> findByIdForUpdate(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_tasks WHERE task_id = :taskId FOR UPDATE";
        MapSqlParameterSource params = new MapSqlParameterSource("taskId", taskId);
        List<Task> results = jdbcTemplate.query(sql, params, taskRowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Task update(Task task) {
        Objects.requireNonNull(task, "task must not be null");

        String sql = "UPDATE chronos_tasks SET " +
                "task_type = :taskType, " +
                "payload = :payload, " +
                "priority = :priority, " +
                "status = :status, " +
                "tenant_id = :tenantId, " +
                "idempotency_key = :idempotencyKey, " +
                "concurrency_key = :concurrencyKey, " +
                "scheduled_at = :scheduledAt, " +
                "deadline = :deadline, " +
                "timeout_ms = :timeoutMs, " +
                "max_attempts = :maxAttempts, " +
                "current_attempt = :currentAttempt, " +
                "version = version + 1 " +
                "WHERE task_id = :taskId AND version = :version";

        MapSqlParameterSource params = toParameterSource(task);

        int rowsUpdated;
        try {
            rowsUpdated = jdbcTemplate.update(sql, params);
        } catch (DuplicateKeyException ex) {
            throw new DuplicateTaskException("Unique constraint violation on task update: " + task.getTaskId(), ex);
        } catch (Exception ex) {
            throw new PersistenceException("Failed to update task " + task.getTaskId(), ex);
        }

        if (rowsUpdated == 0) {
            if (!existsById(task.getTaskId())) {
                throw new TaskNotFoundException(task.getTaskId());
            }
            throw new OptimisticLockingException(task.getTaskId(), task.getVersion());
        }

        return task.toBuilder()
                .version(task.getVersion() + 1)
                .build();
    }

    @Override
    public Optional<Task> findByIdempotencyKey(String tenantId, String idempotencyKey) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_tasks " +
                "WHERE tenant_id = :tenantId AND idempotency_key = :idempotencyKey";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("idempotencyKey", idempotencyKey);

        List<Task> results = jdbcTemplate.query(sql, params, taskRowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public boolean existsById(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "SELECT COUNT(*) FROM chronos_tasks WHERE task_id = :taskId";
        MapSqlParameterSource params = new MapSqlParameterSource("taskId", taskId);
        Integer count = jdbcTemplate.queryForObject(sql, params, Integer.class);
        return count != null && count > 0;
    }

    @Override
    public List<Task> findQueuedTasks(int limit) {
        return findSchedulableTasks(Instant.now(), limit);
    }

    @Override
    public List<Task> findSchedulableTasks(Instant asOf, int limit) {
        int maxLimit = Math.max(1, limit);
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();
        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_tasks " +
                "WHERE status = 'QUEUED' " +
                "  AND (scheduled_at IS NULL OR scheduled_at <= :asOf) " +
                "ORDER BY " +
                "CASE priority " +
                "  WHEN 'CRITICAL' THEN 1 " +
                "  WHEN 'HIGH' THEN 2 " +
                "  WHEN 'NORMAL' THEN 3 " +
                "  WHEN 'LOW' THEN 4 " +
                "  ELSE 5 END ASC, " +
                "scheduled_at ASC NULLS FIRST, " +
                "created_at ASC " +
                "LIMIT :limit";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("asOf", Timestamp.from(effectiveAsOf))
                .addValue("limit", maxLimit);
        return jdbcTemplate.query(sql, params, taskRowMapper);
    }

    @Override
    public List<Task> findStarvedTasks(Instant asOf, Duration maxWaitTime, int limit) {
        int maxLimit = Math.max(1, limit);
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();
        Duration effectiveMaxWait = maxWaitTime != null ? maxWaitTime : Duration.ofMinutes(1);
        Instant cutoff = effectiveAsOf.minus(effectiveMaxWait);

        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_tasks " +
                "WHERE status = 'QUEUED' " +
                "  AND (scheduled_at IS NULL OR scheduled_at <= :asOf) " +
                "  AND created_at <= :cutoff " +
                "ORDER BY created_at ASC " +
                "LIMIT :limit";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("asOf", Timestamp.from(effectiveAsOf))
                .addValue("cutoff", Timestamp.from(cutoff))
                .addValue("limit", maxLimit);
        return jdbcTemplate.query(sql, params, taskRowMapper);
    }

    @Override
    public List<Task> findByStatus(TaskStatus status, int limit) {
        Objects.requireNonNull(status, "status must not be null");
        int maxLimit = Math.max(1, limit);
        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_tasks " +
                "WHERE status = :status ORDER BY created_at ASC LIMIT :limit";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", status.name())
                .addValue("limit", maxLimit);
        return jdbcTemplate.query(sql, params, taskRowMapper);
    }

    @Override
    public int countByTenantAndStatus(String tenantId, TaskStatus status) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        String sql = "SELECT COUNT(*) FROM chronos_tasks WHERE tenant_id = :tenantId AND status = :status";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("status", status.name());
        Integer count = jdbcTemplate.queryForObject(sql, params, Integer.class);
        return count != null ? count : 0;
    }

    @Override
    public int countRunningByTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String sql = "SELECT COUNT(*) FROM chronos_tasks WHERE tenant_id = :tenantId AND status IN ('DISPATCHED', 'RUNNING')";
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
        Integer count = jdbcTemplate.queryForObject(sql, params, Integer.class);
        return count != null ? count : 0;
    }

    @Override
    public List<Task> findByTenantAndStatus(String tenantId, TaskStatus status, int limit) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        int maxLimit = Math.max(1, limit);
        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_tasks " +
                "WHERE tenant_id = :tenantId AND status = :status ORDER BY created_at ASC LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("status", status.name())
                .addValue("limit", maxLimit);
        return jdbcTemplate.query(sql, params, taskRowMapper);
    }

    @Override
    public int countActiveByConcurrencyKey(String tenantId, String concurrencyKey) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(concurrencyKey, "concurrencyKey must not be null");
        String sql = "SELECT COUNT(*) FROM chronos_tasks WHERE tenant_id = :tenantId AND concurrency_key = :concurrencyKey AND status IN ('DISPATCHED', 'RUNNING')";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("concurrencyKey", concurrencyKey);
        Integer count = jdbcTemplate.queryForObject(sql, params, Integer.class);
        return count != null ? count : 0;
    }


    private MapSqlParameterSource toParameterSource(Task task) {
        return new MapSqlParameterSource()
                .addValue("taskId", task.getTaskId())
                .addValue("taskType", task.getTaskType())
                .addValue("payload", task.getPayload())
                .addValue("priority", task.getPriority().name())
                .addValue("status", task.getStatus().name())
                .addValue("tenantId", task.getTenantId())
                .addValue("idempotencyKey", task.getIdempotencyKey())
                .addValue("concurrencyKey", task.getConcurrencyKey())
                .addValue("createdAt", Timestamp.from(task.getCreatedAt()))
                .addValue("scheduledAt", toTimestamp(task.getScheduledAt()))
                .addValue("deadline", toTimestamp(task.getDeadline()))
                .addValue("timeoutMs", task.getTimeout() != null ? task.getTimeout().toMillis() : null)
                .addValue("maxAttempts", task.getMaxAttempts())
                .addValue("currentAttempt", task.getCurrentAttempt())
                .addValue("version", task.getVersion());
    }

    @Override
    public List<Task> findTasksWithFilter(String tenantId, TaskStatus status, String taskType, TaskPriority priority, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLUMNS + " FROM chronos_tasks WHERE 1=1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (tenantId != null && !tenantId.isBlank()) {
            sql.append("AND tenant_id = :tenantId ");
            params.addValue("tenantId", tenantId);
        }
        if (status != null) {
            sql.append("AND status = :status ");
            params.addValue("status", status.name());
        }
        if (taskType != null && !taskType.isBlank()) {
            sql.append("AND task_type = :taskType ");
            params.addValue("taskType", taskType);
        }
        if (priority != null) {
            sql.append("AND priority = :priority ");
            params.addValue("priority", priority.name());
        }

        sql.append("ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", Math.max(1, limit));
        params.addValue("offset", Math.max(0, offset));

        return jdbcTemplate.query(sql.toString(), params, taskRowMapper);
    }

    @Override
    public int countTasksWithFilter(String tenantId, TaskStatus status, String taskType, TaskPriority priority) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM chronos_tasks WHERE 1=1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (tenantId != null && !tenantId.isBlank()) {
            sql.append("AND tenant_id = :tenantId ");
            params.addValue("tenantId", tenantId);
        }
        if (status != null) {
            sql.append("AND status = :status ");
            params.addValue("status", status.name());
        }
        if (taskType != null && !taskType.isBlank()) {
            sql.append("AND task_type = :taskType ");
            params.addValue("taskType", taskType);
        }
        if (priority != null) {
            sql.append("AND priority = :priority ");
            params.addValue("priority", priority.name());
        }

        Integer count = jdbcTemplate.queryForObject(sql.toString(), params, Integer.class);
        return count != null ? count : 0;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private static class TaskRowMapper implements RowMapper<Task> {
        @Override
        public Task mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID taskId = getUuid(rs, "task_id");
            String taskType = rs.getString("task_type");
            String payload = rs.getString("payload");
            TaskPriority priority = TaskPriority.valueOf(rs.getString("priority"));
            TaskStatus status = TaskStatus.valueOf(rs.getString("status"));
            String tenantId = rs.getString("tenant_id");
            String idempotencyKey = rs.getString("idempotency_key");
            String concurrencyKey = rs.getString("concurrency_key");
            Instant createdAt = getInstant(rs, "created_at");
            Instant scheduledAt = getInstant(rs, "scheduled_at");
            Instant deadline = getInstant(rs, "deadline");

            long timeoutMs = rs.getLong("timeout_ms");
            Duration timeout = rs.wasNull() ? null : Duration.ofMillis(timeoutMs);

            int maxAttempts = rs.getInt("max_attempts");
            int currentAttempt = rs.getInt("current_attempt");
            long version = rs.getLong("version");

            return Task.builder()
                    .taskId(taskId)
                    .taskType(taskType)
                    .payload(payload)
                    .priority(priority)
                    .status(status)
                    .tenantId(tenantId)
                    .idempotencyKey(idempotencyKey)
                    .concurrencyKey(concurrencyKey)
                    .createdAt(createdAt)
                    .scheduledAt(scheduledAt)
                    .deadline(deadline)
                    .timeout(timeout)
                    .maxAttempts(maxAttempts)
                    .currentAttempt(currentAttempt)
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
