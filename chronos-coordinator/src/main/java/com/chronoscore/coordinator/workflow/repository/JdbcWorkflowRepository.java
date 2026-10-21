package com.chronoscore.coordinator.workflow.repository;

import com.chronoscore.common.domain.workflow.WorkflowDefinition;
import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.domain.workflow.WorkflowVersion;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.WorkflowVersionStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkflowRepository implements WorkflowRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcWorkflowRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    @Transactional
    public WorkflowDefinition saveWorkflow(WorkflowDefinition workflow) {
        Objects.requireNonNull(workflow, "workflow must not be null");

        String sql = "INSERT INTO chronos_workflows (workflow_id, name, description, created_at, updated_at) " +
                "VALUES (:workflowId, :name, :description, :createdAt, :updatedAt)";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workflowId", workflow.getWorkflowId())
                .addValue("name", workflow.getName())
                .addValue("description", workflow.getDescription())
                .addValue("createdAt", Timestamp.from(workflow.getCreatedAt()))
                .addValue("updatedAt", Timestamp.from(workflow.getUpdatedAt()));

        jdbcTemplate.update(sql, params);
        return workflow;
    }

    @Override
    public Optional<WorkflowDefinition> findWorkflowById(UUID workflowId) {
        String sql = "SELECT workflow_id, name, description, created_at, updated_at " +
                "FROM chronos_workflows WHERE workflow_id = :workflowId";

        try {
            WorkflowDefinition def = jdbcTemplate.queryForObject(sql,
                    new MapSqlParameterSource("workflowId", workflowId),
                    new WorkflowDefinitionRowMapper());
            return Optional.ofNullable(def);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<WorkflowDefinition> findWorkflowByName(String name) {
        String sql = "SELECT workflow_id, name, description, created_at, updated_at " +
                "FROM chronos_workflows WHERE name = :name";

        try {
            WorkflowDefinition def = jdbcTemplate.queryForObject(sql,
                    new MapSqlParameterSource("name", name),
                    new WorkflowDefinitionRowMapper());
            return Optional.ofNullable(def);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public WorkflowVersion saveVersion(WorkflowVersion version) {
        Objects.requireNonNull(version, "version must not be null");

        String versionSql = "INSERT INTO chronos_workflow_versions (version_id, workflow_id, version, status, created_at) " +
                "VALUES (:versionId, :workflowId, :version, :status, :createdAt)";

        MapSqlParameterSource versionParams = new MapSqlParameterSource()
                .addValue("versionId", version.getVersionId())
                .addValue("workflowId", version.getWorkflowId())
                .addValue("version", version.getVersion())
                .addValue("status", version.getStatus().name())
                .addValue("createdAt", Timestamp.from(version.getCreatedAt()));

        jdbcTemplate.update(versionSql, versionParams);

        // Insert nodes
        if (version.getNodes() != null && !version.getNodes().isEmpty()) {
            String nodeSql = "INSERT INTO chronos_workflow_nodes " +
                    "(version_id, node_id, task_type, payload, priority, timeout_ms, max_attempts) " +
                    "VALUES (:versionId, :nodeId, :taskType, :payload, :priority, :timeoutMs, :maxAttempts)";

            List<MapSqlParameterSource> batchParams = new ArrayList<>();
            for (WorkflowNodeDefinition node : version.getNodes()) {
                batchParams.add(new MapSqlParameterSource()
                        .addValue("versionId", version.getVersionId())
                        .addValue("nodeId", node.getNodeId())
                        .addValue("taskType", node.getTaskType())
                        .addValue("payload", node.getPayload())
                        .addValue("priority", node.getPriority().name())
                        .addValue("timeoutMs", node.getTimeout() != null ? node.getTimeout().toMillis() : null)
                        .addValue("maxAttempts", node.getMaxAttempts()));
            }
            jdbcTemplate.batchUpdate(nodeSql, batchParams.toArray(new MapSqlParameterSource[0]));
        }

        // Insert edges
        if (version.getEdges() != null && !version.getEdges().isEmpty()) {
            String edgeSql = "INSERT INTO chronos_workflow_edges (version_id, from_node_id, to_node_id) " +
                    "VALUES (:versionId, :fromNodeId, :toNodeId)";

            List<MapSqlParameterSource> batchParams = new ArrayList<>();
            for (WorkflowEdge edge : version.getEdges()) {
                batchParams.add(new MapSqlParameterSource()
                        .addValue("versionId", version.getVersionId())
                        .addValue("fromNodeId", edge.getFromNodeId())
                        .addValue("toNodeId", edge.getToNodeId()));
            }
            jdbcTemplate.batchUpdate(edgeSql, batchParams.toArray(new MapSqlParameterSource[0]));
        }

        return version;
    }

    @Override
    public Optional<WorkflowVersion> findVersionById(UUID versionId) {
        String sql = "SELECT version_id, workflow_id, version, status, created_at " +
                "FROM chronos_workflow_versions WHERE version_id = :versionId";

        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql,
                    new MapSqlParameterSource("versionId", versionId),
                    (rs, rowNum) -> populateVersionDetails(rs)));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<WorkflowVersion> findVersionByNumber(UUID workflowId, int version) {
        String sql = "SELECT version_id, workflow_id, version, status, created_at " +
                "FROM chronos_workflow_versions WHERE workflow_id = :workflowId AND version = :version";

        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql,
                    new MapSqlParameterSource("workflowId", workflowId).addValue("version", version),
                    (rs, rowNum) -> populateVersionDetails(rs)));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<WorkflowVersion> findLatestPublishedVersion(UUID workflowId) {
        String sql = "SELECT version_id, workflow_id, version, status, created_at " +
                "FROM chronos_workflow_versions " +
                "WHERE workflow_id = :workflowId AND status = 'PUBLISHED' " +
                "ORDER BY version DESC LIMIT 1";

        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql,
                    new MapSqlParameterSource("workflowId", workflowId),
                    (rs, rowNum) -> populateVersionDetails(rs)));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public void updateVersionStatus(UUID versionId, WorkflowVersionStatus status) {
        String sql = "UPDATE chronos_workflow_versions SET status = :status WHERE version_id = :versionId";
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("versionId", versionId)
                .addValue("status", status.name()));
    }

    @Override
    public List<WorkflowNodeDefinition> findNodesByVersion(UUID versionId) {
        String sql = "SELECT node_id, task_type, payload, priority, timeout_ms, max_attempts " +
                "FROM chronos_workflow_nodes WHERE version_id = :versionId ORDER BY node_id ASC";

        return jdbcTemplate.query(sql,
                new MapSqlParameterSource("versionId", versionId),
                new WorkflowNodeDefinitionRowMapper());
    }

    @Override
    public List<WorkflowEdge> findEdgesByVersion(UUID versionId) {
        String sql = "SELECT from_node_id, to_node_id FROM chronos_workflow_edges " +
                "WHERE version_id = :versionId ORDER BY from_node_id ASC, to_node_id ASC";

        return jdbcTemplate.query(sql,
                new MapSqlParameterSource("versionId", versionId),
                (rs, rowNum) -> WorkflowEdge.of(rs.getString("from_node_id"), rs.getString("to_node_id")));
    }

    private WorkflowVersion populateVersionDetails(ResultSet rs) throws SQLException {
        UUID versionId = rs.getObject("version_id", UUID.class);
        UUID workflowId = rs.getObject("workflow_id", UUID.class);
        int versionNum = rs.getInt("version");
        WorkflowVersionStatus status = WorkflowVersionStatus.valueOf(rs.getString("status"));
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        Instant createdAt = createdAtTs != null ? createdAtTs.toInstant() : Instant.now();

        List<WorkflowNodeDefinition> nodes = findNodesByVersion(versionId);
        List<WorkflowEdge> edges = findEdgesByVersion(versionId);

        return WorkflowVersion.builder()
                .versionId(versionId)
                .workflowId(workflowId)
                .version(versionNum)
                .status(status)
                .nodes(nodes)
                .edges(edges)
                .createdAt(createdAt)
                .build();
    }

    @Override
    public List<WorkflowDefinition> findAllWorkflows(int limit, int offset) {
        String sql = "SELECT workflow_id, name, description, created_at, updated_at " +
                "FROM chronos_workflows ORDER BY created_at DESC LIMIT :limit OFFSET :offset";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", Math.max(1, limit))
                .addValue("offset", Math.max(0, offset));
        return jdbcTemplate.query(sql, params, new WorkflowDefinitionRowMapper());
    }

    @Override
    public int countWorkflows() {
        String sql = "SELECT COUNT(*) FROM chronos_workflows";
        Integer count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Integer.class);
        return count != null ? count : 0;
    }

    @Override
    public List<WorkflowVersion> findVersionsByWorkflowId(UUID workflowId) {
        String sql = "SELECT version_id, workflow_id, version, status, created_at " +
                "FROM chronos_workflow_versions WHERE workflow_id = :workflowId ORDER BY version ASC";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workflowId", workflowId);
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            UUID versionId = rs.getObject("version_id", UUID.class);
            List<WorkflowNodeDefinition> nodes = findNodesByVersion(versionId);
            List<WorkflowEdge> edges = findEdgesByVersion(versionId);
            Timestamp createdAt = rs.getTimestamp("created_at");
            return WorkflowVersion.builder()
                    .versionId(versionId)
                    .workflowId(rs.getObject("workflow_id", UUID.class))
                    .version(rs.getInt("version"))
                    .status(WorkflowVersionStatus.valueOf(rs.getString("status")))
                    .nodes(nodes)
                    .edges(edges)
                    .createdAt(createdAt != null ? createdAt.toInstant() : Instant.now())
                    .build();
        });
    }

    private static class WorkflowDefinitionRowMapper implements RowMapper<WorkflowDefinition> {
        @Override
        public WorkflowDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp createdAt = rs.getTimestamp("created_at");
            Timestamp updatedAt = rs.getTimestamp("updated_at");

            return WorkflowDefinition.builder()
                    .workflowId(rs.getObject("workflow_id", UUID.class))
                    .name(rs.getString("name"))
                    .description(rs.getString("description"))
                    .createdAt(createdAt != null ? createdAt.toInstant() : Instant.now())
                    .updatedAt(updatedAt != null ? updatedAt.toInstant() : Instant.now())
                    .build();
        }
    }

    private static class WorkflowNodeDefinitionRowMapper implements RowMapper<WorkflowNodeDefinition> {
        @Override
        public WorkflowNodeDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
            long timeoutMs = rs.getLong("timeout_ms");
            Duration timeout = rs.wasNull() ? null : Duration.ofMillis(timeoutMs);

            return WorkflowNodeDefinition.builder()
                    .nodeId(rs.getString("node_id"))
                    .taskType(rs.getString("task_type"))
                    .payload(rs.getString("payload"))
                    .priority(TaskPriority.valueOf(rs.getString("priority")))
                    .timeout(timeout)
                    .maxAttempts(rs.getInt("max_attempts"))
                    .build();
        }
    }
}
