package com.chronoscore.coordinator.workflow.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Repository
public class JdbcWorkflowAuditRepository implements WorkflowAuditRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcWorkflowAuditRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    @Transactional
    public void appendAudit(UUID executionId, String fromStatus, String toStatus, String reason, String metadata) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(toStatus, "toStatus must not be null");

        String sql = "INSERT INTO chronos_workflow_audit_log (" +
                "audit_id, execution_id, from_status, to_status, reason, metadata, created_at" +
                ") VALUES (" +
                ":auditId, :executionId, :fromStatus, :toStatus, :reason, :metadata, :createdAt" +
                ")";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("auditId", UUID.randomUUID())
                .addValue("executionId", executionId)
                .addValue("fromStatus", fromStatus)
                .addValue("toStatus", toStatus)
                .addValue("reason", reason)
                .addValue("metadata", metadata)
                .addValue("createdAt", Timestamp.from(Instant.now()));

        jdbcTemplate.update(sql, params);
    }
}
