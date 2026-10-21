package com.chronoscore.ai.tools;

import com.chronoscore.ai.dto.AiEvidenceRecord;
import com.chronoscore.ai.ports.AiClusterOperationsPort;
import com.chronoscore.ai.ports.AiDlqOperationsPort;
import com.chronoscore.ai.ports.AiTaskOperationsPort;
import com.chronoscore.ai.ports.AiTenantOperationsPort;
import com.chronoscore.ai.ports.AiWorkerOperationsPort;
import com.chronoscore.ai.ports.AiWorkflowOperationsPort;
import com.chronoscore.common.dto.TaskSummaryDTO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Registry of statically defined, permission-checked operational tools.
 * Provides controlled data-gathering tools for the AI Operations Agent.
 */
public class AiToolRegistry {

    private final Map<String, AiTool> registeredTools = new LinkedHashMap<>();

    private final AiClusterOperationsPort clusterPort;
    private final AiWorkerOperationsPort workerPort;
    private final AiTaskOperationsPort taskPort;
    private final AiDlqOperationsPort dlqPort;
    private final AiWorkflowOperationsPort workflowPort;
    private final AiTenantOperationsPort tenantPort;

    public AiToolRegistry(AiClusterOperationsPort clusterPort,
                          AiWorkerOperationsPort workerPort,
                          AiTaskOperationsPort taskPort,
                          AiDlqOperationsPort dlqPort,
                          AiWorkflowOperationsPort workflowPort,
                          AiTenantOperationsPort tenantPort) {
        this.clusterPort = Objects.requireNonNull(clusterPort, "clusterPort must not be null");
        this.workerPort = Objects.requireNonNull(workerPort, "workerPort must not be null");
        this.taskPort = Objects.requireNonNull(taskPort, "taskPort must not be null");
        this.dlqPort = Objects.requireNonNull(dlqPort, "dlqPort must not be null");
        this.workflowPort = Objects.requireNonNull(workflowPort, "workflowPort must not be null");
        this.tenantPort = Objects.requireNonNull(tenantPort, "tenantPort must not be null");

        registerStandardTools();
    }

    private void registerStandardTools() {
        // 1. Cluster Status Tool
        register(new AiTool("cluster.status", "Retrieves coordinator leader status, epoch, and cluster capacity",
                Map.of("type", "object", "properties", Map.of()), ToolPermission.READ_ONLY, true, false));

        // 2. Worker List Tool
        register(new AiTool("workers.list", "Lists all registered workers with health states and capacity",
                Map.of("type", "object", "properties", Map.of()), ToolPermission.READ_ONLY, true, false));

        // 3. Worker Inspect Tool
        register(new AiTool("worker.inspect", "Inspects a specific worker node's telemetry and supported task types",
                Map.of("type", "object", "required", List.of("workerId"), "properties", Map.of("workerId", Map.of("type", "string"))),
                ToolPermission.READ_ONLY, true, false));

        // 4. Tasks Search Tool
        register(new AiTool("tasks.search", "Searches tasks within a tenant with bounded limits",
                Map.of("type", "object", "properties", Map.of("tenantId", Map.of("type", "string"), "status", Map.of("type", "string"), "limit", Map.of("type", "integer"))),
                ToolPermission.READ_ONLY, true, true));

        // 5. Task Inspect Tool
        register(new AiTool("task.inspect", "Inspects full lifecycle details, attempts, and error codes for a specific task",
                Map.of("type", "object", "required", List.of("taskId"), "properties", Map.of("taskId", Map.of("type", "string"))),
                ToolPermission.READ_ONLY, true, false));

        // 6. DLQ List Tool
        register(new AiTool("dlq.list", "Lists dead-letter queue tasks and failure reasons for a tenant",
                Map.of("type", "object", "properties", Map.of("tenantId", Map.of("type", "string"), "limit", Map.of("type", "integer"))),
                ToolPermission.READ_ONLY, true, true));

        // 7. DLQ Inspect Tool
        register(new AiTool("dlq.inspect", "Inspects historical attempt failures and audit log for a DLQ task",
                Map.of("type", "object", "required", List.of("taskId"), "properties", Map.of("taskId", Map.of("type", "string"))),
                ToolPermission.READ_ONLY, true, false));

        // 8. Workflow Execution Inspect Tool
        register(new AiTool("workflow.execution.inspect", "Inspects live DAG execution state and node statuses",
                Map.of("type", "object", "required", List.of("executionId"), "properties", Map.of("executionId", Map.of("type", "string"))),
                ToolPermission.READ_ONLY, true, false));

        // 9. Tenant Policy Inspect Tool
        register(new AiTool("tenant.policy.inspect", "Inspects concurrency and rate limit policy quotas for a tenant",
                Map.of("type", "object", "required", List.of("tenantId"), "properties", Map.of("tenantId", Map.of("type", "string"))),
                ToolPermission.READ_ONLY, true, true));
    }

    public void register(AiTool tool) {
        registeredTools.put(tool.name(), tool);
    }

    public Map<String, AiTool> getRegisteredTools() {
        return Collections.unmodifiableMap(registeredTools);
    }

    public Optional<AiTool> getTool(String name) {
        return Optional.ofNullable(registeredTools.get(name));
    }

    // --- Operational Evidence Collectors ---

    public List<AiEvidenceRecord> collectClusterEvidence() {
        List<AiEvidenceRecord> evidence = new ArrayList<>();
        try {
            AiClusterOperationsPort.ClusterTelemetry cluster = clusterPort.getClusterOverview();
            evidence.add(new AiEvidenceRecord("cluster", cluster.leaderId() != null ? cluster.leaderId() : "coordinator",
                    String.format("Leader epoch %d, Active workers: %d, Suspected: %d, Total capacity: %d",
                            cluster.leaderEpoch(), cluster.activeWorkers(), cluster.suspectedWorkers(), cluster.totalCapacity()),
                    cluster.leader() ? "ACTIVE_LEADER" : "FOLLOWER"));

            List<AiWorkerOperationsPort.WorkerSummary> workers = workerPort.listWorkers();
            for (AiWorkerOperationsPort.WorkerSummary w : workers) {
                evidence.add(new AiEvidenceRecord("worker", w.workerId(),
                        String.format("State: %s, Tasks: %d/%d capacity, Last heartbeat: %s",
                                w.state(), w.activeTasks(), w.totalCapacity(), w.lastHeartbeat()),
                        w.state()));
            }
        } catch (Exception e) {
            evidence.add(new AiEvidenceRecord("cluster", "coordinator", "Telemetry error: " + e.getMessage(), "ERROR"));
        }
        return evidence;
    }

    public List<AiEvidenceRecord> collectWorkerEvidence(String workerId) {
        List<AiEvidenceRecord> evidence = new ArrayList<>();
        try {
            Optional<AiWorkerOperationsPort.WorkerDetail> workerOpt = workerPort.getWorker(workerId);
            if (workerOpt.isPresent()) {
                AiWorkerOperationsPort.WorkerDetail w = workerOpt.get();
                evidence.add(new AiEvidenceRecord("worker", w.workerId(),
                        String.format("State: %s, Capacity: %d/%d, Supported types: %s",
                                w.state(), w.activeTasks(), w.totalCapacity(), w.supportedTaskTypes()),
                        w.state()));
            } else {
                evidence.add(new AiEvidenceRecord("worker", workerId, "Worker not found in cluster registry", "NOT_FOUND"));
            }
        } catch (Exception e) {
            evidence.add(new AiEvidenceRecord("worker", workerId, "Worker telemetry error: " + e.getMessage(), "ERROR"));
        }
        return evidence;
    }

    public List<AiEvidenceRecord> collectTaskEvidence(UUID taskId) {
        List<AiEvidenceRecord> evidence = new ArrayList<>();
        try {
            Optional<TaskSummaryDTO> taskOpt = taskPort.getTask(taskId);
            if (taskOpt.isPresent()) {
                TaskSummaryDTO t = taskOpt.get();
                evidence.add(new AiEvidenceRecord("task", t.taskId().toString(),
                        String.format("Type: %s, Priority: %s, Attempts: %d/%d, Tenant: %s",
                                t.taskType(), t.priority(), t.currentAttempt(), t.maxAttempts(), t.tenantId()),
                        t.status().name()));
            } else {
                evidence.add(new AiEvidenceRecord("task", taskId.toString(), "Task record not found", "NOT_FOUND"));
            }
        } catch (Exception e) {
            evidence.add(new AiEvidenceRecord("task", taskId.toString(), "Task inspection error: " + e.getMessage(), "ERROR"));
        }
        return evidence;
    }

    public List<AiEvidenceRecord> collectDlqEvidence(String tenantId, UUID taskId) {
        List<AiEvidenceRecord> evidence = new ArrayList<>();
        try {
            if (taskId != null) {
                Optional<AiDlqOperationsPort.DlqDetail> dlqOpt = dlqPort.inspectDlq(taskId);
                if (dlqOpt.isPresent()) {
                    AiDlqOperationsPort.DlqDetail d = dlqOpt.get();
                    evidence.add(new AiEvidenceRecord("dlq", d.taskId().toString(),
                            String.format("Type: %s, Reason: %s, Attempts: %d, Errors: %s",
                                    d.taskType(), d.failureReason(), d.attemptCount(), d.attemptErrors()),
                            "DLQ"));
                } else {
                    evidence.add(new AiEvidenceRecord("dlq", taskId.toString(), "DLQ record not found", "NOT_FOUND"));
                }
            } else {
                List<AiDlqOperationsPort.DlqSummary> dlqList = dlqPort.listDlq(tenantId, 10);
                for (AiDlqOperationsPort.DlqSummary d : dlqList) {
                    evidence.add(new AiEvidenceRecord("dlq", d.taskId().toString(),
                            String.format("Type: %s, Failed after %d attempts: %s", d.taskType(), d.attemptCount(), d.failureReason()),
                            "DLQ"));
                }
            }
        } catch (Exception e) {
            evidence.add(new AiEvidenceRecord("dlq", taskId != null ? taskId.toString() : "all", "DLQ inspection error: " + e.getMessage(), "ERROR"));
        }
        return evidence;
    }
}
