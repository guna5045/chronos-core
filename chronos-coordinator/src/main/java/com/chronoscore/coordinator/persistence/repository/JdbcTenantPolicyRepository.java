package com.chronoscore.coordinator.persistence.repository;

import com.chronoscore.common.domain.TenantExecutionPolicy;
import com.chronoscore.coordinator.persistence.exception.PersistenceException;
import org.springframework.dao.DuplicateKeyException;
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

/**
 * JDBC implementation of {@link TenantPolicyRepository}.
 */
@Repository
public class JdbcTenantPolicyRepository implements TenantPolicyRepository {

    private static final String SELECT_COLUMNS =
            "tenant_id, max_concurrent_tasks, max_queued_tasks, tasks_per_second, " +
            "burst_capacity, max_workflow_executions, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<TenantExecutionPolicy> rowMapper = new TenantPolicyRowMapper();

    public JdbcTenantPolicyRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public TenantExecutionPolicy save(TenantExecutionPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");

        String sql = "INSERT INTO chronos_tenant_policies (" +
                "tenant_id, max_concurrent_tasks, max_queued_tasks, tasks_per_second, " +
                "burst_capacity, max_workflow_executions, created_at, updated_at" +
                ") VALUES (" +
                ":tenantId, :maxConcurrentTasks, :maxQueuedTasks, :tasksPerSecond, " +
                ":burstCapacity, :maxWorkflowExecutions, :createdAt, :updatedAt" +
                ")";

        MapSqlParameterSource params = toParams(policy);

        try {
            jdbcTemplate.update(sql, params);
            return policy;
        } catch (DuplicateKeyException e) {
            throw new PersistenceException("Policy already exists for tenant: " + policy.getTenantId(), e);
        } catch (Exception e) {
            throw new PersistenceException("Failed to persist tenant policy for " + policy.getTenantId(), e);
        }
    }

    @Override
    public Optional<TenantExecutionPolicy> findByTenantId(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        String sql = "SELECT " + SELECT_COLUMNS + " FROM chronos_tenant_policies WHERE tenant_id = :tenantId";
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
        List<TenantExecutionPolicy> results = jdbcTemplate.query(sql, params, rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public TenantExecutionPolicy update(TenantExecutionPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");

        String sql = "UPDATE chronos_tenant_policies SET " +
                "max_concurrent_tasks = :maxConcurrentTasks, " +
                "max_queued_tasks = :maxQueuedTasks, " +
                "tasks_per_second = :tasksPerSecond, " +
                "burst_capacity = :burstCapacity, " +
                "max_workflow_executions = :maxWorkflowExecutions, " +
                "updated_at = :updatedAt " +
                "WHERE tenant_id = :tenantId";

        Instant now = Instant.now();
        TenantExecutionPolicy updated = policy.toBuilder().updatedAt(now).build();
        MapSqlParameterSource params = toParams(updated);

        int rows = jdbcTemplate.update(sql, params);
        if (rows == 0) {
            throw new PersistenceException("No tenant policy found to update for tenant: " + policy.getTenantId());
        }
        return updated;
    }

    @Override
    public TenantExecutionPolicy getEffectivePolicy(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return findByTenantId(tenantId).orElseGet(() -> TenantExecutionPolicy.defaultPolicy(tenantId));
    }

    @Override
    public boolean delete(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String sql = "DELETE FROM chronos_tenant_policies WHERE tenant_id = :tenantId";
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
        return jdbcTemplate.update(sql, params) > 0;
    }

    private MapSqlParameterSource toParams(TenantExecutionPolicy policy) {
        return new MapSqlParameterSource()
                .addValue("tenantId", policy.getTenantId())
                .addValue("maxConcurrentTasks", policy.getMaxConcurrentTasks())
                .addValue("maxQueuedTasks", policy.getMaxQueuedTasks())
                .addValue("tasksPerSecond", policy.getTasksPerSecond())
                .addValue("burstCapacity", policy.getBurstCapacity())
                .addValue("maxWorkflowExecutions", policy.getMaxWorkflowExecutions())
                .addValue("createdAt", Timestamp.from(policy.getCreatedAt()))
                .addValue("updatedAt", Timestamp.from(policy.getUpdatedAt()));
    }

    private static class TenantPolicyRowMapper implements RowMapper<TenantExecutionPolicy> {
        @Override
        public TenantExecutionPolicy mapRow(ResultSet rs, int rowNum) throws SQLException {
            String tenantId = rs.getString("tenant_id");
            int maxConcurrent = rs.getInt("max_concurrent_tasks");
            int maxQueued = rs.getInt("max_queued_tasks");
            double tps = rs.getDouble("tasks_per_second");
            int burst = rs.getInt("burst_capacity");
            int maxWf = rs.getInt("max_workflow_executions");
            Instant createdAt = rs.getTimestamp("created_at").toInstant();
            Instant updatedAt = rs.getTimestamp("updated_at").toInstant();

            return TenantExecutionPolicy.builder()
                    .tenantId(tenantId)
                    .maxConcurrentTasks(maxConcurrent)
                    .maxQueuedTasks(maxQueued)
                    .tasksPerSecond(tps)
                    .burstCapacity(burst)
                    .maxWorkflowExecutions(maxWf)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .build();
        }
    }
}
