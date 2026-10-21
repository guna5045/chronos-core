package com.chronoscore.coordinator.workflow.repository;

import com.chronoscore.common.domain.workflow.WorkflowExecution;
import com.chronoscore.common.domain.workflow.WorkflowExecutionNode;
import com.chronoscore.common.enums.WorkflowNodeStatus;
import com.chronoscore.common.enums.WorkflowStatus;
import com.chronoscore.coordinator.persistence.exception.OptimisticLockingException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkflowExecutionRepository implements WorkflowExecutionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<WorkflowExecution> executionRowMapper = new WorkflowExecutionRowMapper();
    private final RowMapper<WorkflowExecutionNode> nodeRowMapper = new WorkflowExecutionNodeRowMapper();

    public JdbcWorkflowExecutionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    @Transactional
    public WorkflowExecution saveExecution(WorkflowExecution execution) {
        Objects.requireNonNull(execution, "execution must not be null");

        String sql = "INSERT INTO chronos_workflow_executions (" +
                "execution_id, workflow_id, version_id, status, tenant_id, created_at, started_at, completed_at, version" +
                ") VALUES (" +
                ":executionId, :workflowId, :versionId, :status, :tenantId, :createdAt, :startedAt, :completedAt, :version" +
                ")";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("executionId", execution.getExecutionId())
                .addValue("workflowId", execution.getWorkflowId())
                .addValue("versionId", execution.getVersionId())
                .addValue("status", execution.getStatus().name())
                .addValue("tenantId", execution.getTenantId())
                .addValue("createdAt", Timestamp.from(execution.getCreatedAt()))
                .addValue("startedAt", execution.getStartedAt() != null ? Timestamp.from(execution.getStartedAt()) : null)
                .addValue("completedAt", execution.getCompletedAt() != null ? Timestamp.from(execution.getCompletedAt()) : null)
                .addValue("version", execution.getVersion());

        jdbcTemplate.update(sql, params);
        return execution;
    }

    @Override
    @Transactional
    public WorkflowExecution updateExecution(WorkflowExecution execution) {
        Objects.requireNonNull(execution, "execution must not be null");

        String sql = "UPDATE chronos_workflow_executions SET " +
                "status = :status, " +
                "started_at = :startedAt, " +
                "completed_at = :completedAt, " +
                "version = version + 1 " +
                "WHERE execution_id = :executionId AND version = :version";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("executionId", execution.getExecutionId())
                .addValue("status", execution.getStatus().name())
                .addValue("startedAt", execution.getStartedAt() != null ? Timestamp.from(execution.getStartedAt()) : null)
                .addValue("completedAt", execution.getCompletedAt() != null ? Timestamp.from(execution.getCompletedAt()) : null)
                .addValue("version", execution.getVersion());

        int rows = jdbcTemplate.update(sql, params);
        if (rows == 0) {
            throw new OptimisticLockingException(String.format(
                    "Stale update detected for WorkflowExecution [%s] at version [%d]",
                    execution.getExecutionId(), execution.getVersion()));
        }

        return execution.toBuilder()
                .version(execution.getVersion() + 1)
                .build();
    }

    @Override
    public Optional<WorkflowExecution> findExecutionById(UUID executionId) {
        String sql = "SELECT execution_id, workflow_id, version_id, status, tenant_id, " +
                "created_at, started_at, completed_at, version " +
                "FROM chronos_workflow_executions WHERE execution_id = :executionId";

        try {
            WorkflowExecution exec = jdbcTemplate.queryForObject(sql,
                    new MapSqlParameterSource("executionId", executionId),
                    executionRowMapper);
            return Optional.ofNullable(exec);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public WorkflowExecutionNode saveExecutionNode(WorkflowExecutionNode node) {
        Objects.requireNonNull(node, "node must not be null");

        String sql = "INSERT INTO chronos_workflow_execution_nodes (" +
                "execution_id, node_id, task_id, status, started_at, completed_at, error_code, error_message, version" +
                ") VALUES (" +
                ":executionId, :nodeId, :taskId, :status, :startedAt, :completedAt, :errorCode, :errorMessage, :version" +
                ")";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("executionId", node.getExecutionId())
                .addValue("nodeId", node.getNodeId())
                .addValue("taskId", node.getTaskId())
                .addValue("status", node.getStatus().name())
                .addValue("startedAt", node.getStartedAt() != null ? Timestamp.from(node.getStartedAt()) : null)
                .addValue("completedAt", node.getCompletedAt() != null ? Timestamp.from(node.getCompletedAt()) : null)
                .addValue("errorCode", node.getErrorCode())
                .addValue("errorMessage", node.getErrorMessage())
                .addValue("version", node.getVersion());

        jdbcTemplate.update(sql, params);
        return node;
    }

    @Override
    @Transactional
    public void saveExecutionNodes(List<WorkflowExecutionNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO chronos_workflow_execution_nodes (" +
                "execution_id, node_id, task_id, status, started_at, completed_at, error_code, error_message, version" +
                ") VALUES (" +
                ":executionId, :nodeId, :taskId, :status, :startedAt, :completedAt, :errorCode, :errorMessage, :version" +
                ")";

        List<MapSqlParameterSource> batchParams = new ArrayList<>();
        for (WorkflowExecutionNode node : nodes) {
            batchParams.add(new MapSqlParameterSource()
                    .addValue("executionId", node.getExecutionId())
                    .addValue("nodeId", node.getNodeId())
                    .addValue("taskId", node.getTaskId())
                    .addValue("status", node.getStatus().name())
                    .addValue("startedAt", node.getStartedAt() != null ? Timestamp.from(node.getStartedAt()) : null)
                    .addValue("completedAt", node.getCompletedAt() != null ? Timestamp.from(node.getCompletedAt()) : null)
                    .addValue("errorCode", node.getErrorCode())
                    .addValue("errorMessage", node.getErrorMessage())
                    .addValue("version", node.getVersion()));
        }

        jdbcTemplate.batchUpdate(sql, batchParams.toArray(new MapSqlParameterSource[0]));
    }

    @Override
    @Transactional
    public WorkflowExecutionNode updateExecutionNode(WorkflowExecutionNode node) {
        Objects.requireNonNull(node, "node must not be null");

        String sql = "UPDATE chronos_workflow_execution_nodes SET " +
                "task_id = :taskId, " +
                "status = :status, " +
                "started_at = :startedAt, " +
                "completed_at = :completedAt, " +
                "error_code = :errorCode, " +
                "error_message = :errorMessage, " +
                "version = version + 1 " +
                "WHERE execution_id = :executionId AND node_id = :nodeId AND version = :version";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("executionId", node.getExecutionId())
                .addValue("nodeId", node.getNodeId())
                .addValue("taskId", node.getTaskId())
                .addValue("status", node.getStatus().name())
                .addValue("startedAt", node.getStartedAt() != null ? Timestamp.from(node.getStartedAt()) : null)
                .addValue("completedAt", node.getCompletedAt() != null ? Timestamp.from(node.getCompletedAt()) : null)
                .addValue("errorCode", node.getErrorCode())
                .addValue("errorMessage", node.getErrorMessage())
                .addValue("version", node.getVersion());

        int rows = jdbcTemplate.update(sql, params);
        if (rows == 0) {
            throw new OptimisticLockingException(String.format(
                    "Stale update detected for WorkflowExecutionNode [%s] in execution [%s] at version [%d]",
                    node.getNodeId(), node.getExecutionId(), node.getVersion()));
        }

        return node.toBuilder()
                .version(node.getVersion() + 1)
                .build();
    }

    @Override
    public Optional<WorkflowExecutionNode> findExecutionNode(UUID executionId, String nodeId) {
        String sql = "SELECT execution_id, node_id, task_id, status, started_at, completed_at, " +
                "error_code, error_message, version " +
                "FROM chronos_workflow_execution_nodes " +
                "WHERE execution_id = :executionId AND node_id = :nodeId";

        try {
            WorkflowExecutionNode node = jdbcTemplate.queryForObject(sql,
                    new MapSqlParameterSource("executionId", executionId).addValue("nodeId", nodeId),
                    nodeRowMapper);
            return Optional.ofNullable(node);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<WorkflowExecutionNode> findExecutionNodeByTaskId(UUID taskId) {
        String sql = "SELECT execution_id, node_id, task_id, status, started_at, completed_at, " +
                "error_code, error_message, version " +
                "FROM chronos_workflow_execution_nodes WHERE task_id = :taskId";

        try {
            WorkflowExecutionNode node = jdbcTemplate.queryForObject(sql,
                    new MapSqlParameterSource("taskId", taskId),
                    nodeRowMapper);
            return Optional.ofNullable(node);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<WorkflowExecutionNode> findExecutionNodes(UUID executionId) {
        String sql = "SELECT execution_id, node_id, task_id, status, started_at, completed_at, " +
                "error_code, error_message, version " +
                "FROM chronos_workflow_execution_nodes " +
                "WHERE execution_id = :executionId ORDER BY node_id ASC";

        return jdbcTemplate.query(sql,
                new MapSqlParameterSource("executionId", executionId),
                nodeRowMapper);
    }

    @Override
    public boolean areAllPredecessorsSucceeded(UUID executionId, UUID versionId, String nodeId) {
        String sql = "SELECT COUNT(*) FROM chronos_workflow_edges e " +
                "LEFT JOIN chronos_workflow_execution_nodes en " +
                "ON en.execution_id = :executionId AND en.node_id = e.from_node_id " +
                "WHERE e.version_id = :versionId AND e.to_node_id = :nodeId " +
                "AND (en.status IS NULL OR en.status != 'SUCCEEDED')";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("executionId", executionId)
                .addValue("versionId", versionId)
                .addValue("nodeId", nodeId);

        Integer unsatisfiedCount = jdbcTemplate.queryForObject(sql, params, Integer.class);
        return unsatisfiedCount != null && unsatisfiedCount == 0;
    }

    @Override
    public int countNonSucceededNodes(UUID executionId) {
        String sql = "SELECT COUNT(*) FROM chronos_workflow_execution_nodes " +
                "WHERE execution_id = :executionId AND status != 'SUCCEEDED'";

        Integer count = jdbcTemplate.queryForObject(sql,
                new MapSqlParameterSource("executionId", executionId),
                Integer.class);
        return count != null ? count : 0;
    }

    @Override
    @Transactional
    public boolean claimNodeForActivation(UUID executionId, String nodeId, Instant startedAt) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        String sql = "UPDATE chronos_workflow_execution_nodes SET " +
                "status = :newStatus, " +
                "started_at = :startedAt, " +
                "version = version + 1 " +
                "WHERE execution_id = :executionId AND node_id = :nodeId AND status = :expectedStatus";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("newStatus", WorkflowNodeStatus.READY.name())
                .addValue("startedAt", startedAt != null ? Timestamp.from(startedAt) : Timestamp.from(Instant.now()))
                .addValue("executionId", executionId)
                .addValue("nodeId", nodeId)
                .addValue("expectedStatus", WorkflowNodeStatus.PENDING.name());

        int rows = jdbcTemplate.update(sql, params);
        return rows > 0;
    }

    @Override
    @Transactional
    public void linkTaskToNode(UUID executionId, String nodeId, UUID taskId) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");

        String sql = "UPDATE chronos_workflow_execution_nodes SET " +
                "task_id = :taskId, " +
                "version = version + 1 " +
                "WHERE execution_id = :executionId AND node_id = :nodeId";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("executionId", executionId)
                .addValue("nodeId", nodeId)
                .addValue("taskId", taskId);

        jdbcTemplate.update(sql, params);
    }

    private static class WorkflowExecutionRowMapper implements RowMapper<WorkflowExecution> {
        @Override
        public WorkflowExecution mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp createdAt = rs.getTimestamp("created_at");
            Timestamp startedAt = rs.getTimestamp("started_at");
            Timestamp completedAt = rs.getTimestamp("completed_at");

            return WorkflowExecution.builder()
                    .executionId(rs.getObject("execution_id", UUID.class))
                    .workflowId(rs.getObject("workflow_id", UUID.class))
                    .versionId(rs.getObject("version_id", UUID.class))
                    .status(WorkflowStatus.valueOf(rs.getString("status")))
                    .tenantId(rs.getString("tenant_id"))
                    .createdAt(createdAt != null ? createdAt.toInstant() : Instant.now())
                    .startedAt(startedAt != null ? startedAt.toInstant() : null)
                    .completedAt(completedAt != null ? completedAt.toInstant() : null)
                    .version(rs.getLong("version"))
                    .build();
        }
    }

    private static class WorkflowExecutionNodeRowMapper implements RowMapper<WorkflowExecutionNode> {
        @Override
        public WorkflowExecutionNode mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp startedAt = rs.getTimestamp("started_at");
            Timestamp completedAt = rs.getTimestamp("completed_at");

            return WorkflowExecutionNode.builder()
                    .executionId(rs.getObject("execution_id", UUID.class))
                    .nodeId(rs.getString("node_id"))
                    .taskId(rs.getObject("task_id", UUID.class))
                    .status(WorkflowNodeStatus.valueOf(rs.getString("status")))
                    .startedAt(startedAt != null ? startedAt.toInstant() : null)
                    .completedAt(completedAt != null ? completedAt.toInstant() : null)
                    .errorCode(rs.getString("error_code"))
                    .errorMessage(rs.getString("error_message"))
                    .version(rs.getLong("version"))
                    .build();
        }
    }
}
