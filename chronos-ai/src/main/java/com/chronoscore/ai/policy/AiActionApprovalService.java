package com.chronoscore.ai.policy;

import com.chronoscore.ai.audit.AiAuditService;
import com.chronoscore.ai.dto.AiActionStatus;
import com.chronoscore.ai.dto.AiOperationalAction;
import com.chronoscore.ai.dto.AiProposalStatus;
import com.chronoscore.ai.dto.AiWorkflowProposal;
import com.chronoscore.ai.ports.AiDlqOperationsPort;
import com.chronoscore.ai.ports.AiTaskOperationsPort;
import com.chronoscore.ai.ports.AiWorkflowOperationsPort;
import com.chronoscore.common.dto.TaskSummaryDTO;
import com.chronoscore.common.enums.TaskStatus;
import com.chronoscore.common.validation.WorkflowDagValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service enforcing policy, authorization, expiration, and STALE STATE PROTECTION
 * before human operators can execute AI recommendations or materialize workflow proposals.
 */
public class AiActionApprovalService {

    private static final Logger log = LoggerFactory.getLogger(AiActionApprovalService.class);

    private final Map<UUID, AiWorkflowProposal> proposals = new ConcurrentHashMap<>();
    private final Map<UUID, AiOperationalAction> actions = new ConcurrentHashMap<>();

    private final AiTaskOperationsPort taskPort;
    private final AiDlqOperationsPort dlqPort;
    private final AiWorkflowOperationsPort workflowPort;
    private final AiAuditService auditService;

    public AiActionApprovalService(AiTaskOperationsPort taskPort,
                                   AiDlqOperationsPort dlqPort,
                                   AiWorkflowOperationsPort workflowPort,
                                   AiAuditService auditService) {
        this.taskPort = Objects.requireNonNull(taskPort, "taskPort must not be null");
        this.dlqPort = Objects.requireNonNull(dlqPort, "dlqPort must not be null");
        this.workflowPort = Objects.requireNonNull(workflowPort, "workflowPort must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
    }

    // --- Proposal Management ---

    public void registerProposal(AiWorkflowProposal proposal) {
        proposals.put(proposal.proposalId(), proposal);
    }

    public Optional<AiWorkflowProposal> getProposal(UUID proposalId) {
        return Optional.ofNullable(proposals.get(proposalId));
    }

    public List<AiWorkflowProposal> listProposals(String tenantId) {
        List<AiWorkflowProposal> list = new ArrayList<>();
        for (AiWorkflowProposal p : proposals.values()) {
            if (tenantId == null || tenantId.isBlank() || tenantId.equalsIgnoreCase(p.tenantId())) {
                list.add(p);
            }
        }
        return list;
    }

    public AiWorkflowOperationsPort.WorkflowCreatedResult approveWorkflow(UUID proposalId, String principal,
                                                                          Set<String> roles, String callerTenantId) {
        AiWorkflowProposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            throw new IllegalArgumentException("Workflow proposal not found: " + proposalId);
        }

        // 1. RBAC check
        assertOperatorOrAdmin(roles);

        // 2. Tenant check
        assertTenantAccess(proposal.tenantId(), callerTenantId, roles);

        // 3. Status check
        if (proposal.status() != AiProposalStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Proposal is not in PENDING_REVIEW state (current: " + proposal.status() + ")");
        }

        // 4. Expiration check
        if (Instant.now().isAfter(proposal.expiresAt())) {
            throw new AiActionExpiredException("Workflow proposal " + proposalId + " has expired");
        }

        // 5. Deterministic DAG Revalidation
        WorkflowDagValidator.validate(proposal.nodes(), proposal.edges());

        // 6. Materialize through authoritative WorkflowService via Port
        AiWorkflowOperationsPort.WorkflowCreatedResult result = workflowPort.createWorkflow(
                proposal.workflowName(), proposal.description(), proposal.nodes(), proposal.edges()
        );

        // 7. Update status & audit
        AiWorkflowProposal updated = AiWorkflowProposal.builder()
                .proposalId(proposal.proposalId())
                .tenantId(proposal.tenantId())
                .workflowName(proposal.workflowName())
                .description(proposal.description())
                .nodes(proposal.nodes())
                .edges(proposal.edges())
                .explanation(proposal.explanation())
                .valid(true)
                .status(AiProposalStatus.APPROVED)
                .createdAt(proposal.createdAt())
                .expiresAt(proposal.expiresAt())
                .build();
        proposals.put(proposalId, updated);

        auditService.record("AI_WORKFLOW_AGENT", principal, proposal.tenantId(), "APPROVE_WORKFLOW",
                result.workflowId().toString(), true, "Materialized workflow " + result.name() + " v" + result.version());

        return result;
    }

    public void rejectWorkflow(UUID proposalId, String principal, String reason) {
        AiWorkflowProposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            throw new IllegalArgumentException("Workflow proposal not found: " + proposalId);
        }

        AiWorkflowProposal updated = AiWorkflowProposal.builder()
                .proposalId(proposal.proposalId())
                .tenantId(proposal.tenantId())
                .workflowName(proposal.workflowName())
                .description(proposal.description())
                .nodes(proposal.nodes())
                .edges(proposal.edges())
                .explanation(proposal.explanation())
                .valid(proposal.valid())
                .status(AiProposalStatus.REJECTED)
                .createdAt(proposal.createdAt())
                .expiresAt(proposal.expiresAt())
                .build();
        proposals.put(proposalId, updated);

        auditService.record("AI_WORKFLOW_AGENT", principal, proposal.tenantId(), "REJECT_WORKFLOW",
                proposalId.toString(), true, "Reason: " + (reason != null ? reason : "Operator rejected"));
    }

    // --- Action Recommendation Management ---

    public void registerAction(AiOperationalAction action) {
        actions.put(action.actionId(), action);
    }

    public Optional<AiOperationalAction> getAction(UUID actionId) {
        return Optional.ofNullable(actions.get(actionId));
    }

    public List<AiOperationalAction> listActions(String tenantId, AiActionStatus status) {
        List<AiOperationalAction> list = new ArrayList<>();
        Instant now = Instant.now();
        for (AiOperationalAction a : actions.values()) {
            // Lazy expire
            if (a.status() == AiActionStatus.PENDING_APPROVAL && now.isAfter(a.expiresAt())) {
                a = expireAction(a);
            }
            if (status != null && a.status() != status) {
                continue;
            }
            if (tenantId != null && !tenantId.isBlank() && a.tenantId() != null && !tenantId.equalsIgnoreCase(a.tenantId())) {
                continue;
            }
            list.add(a);
        }
        return list;
    }

    public AiOperationalAction approveAction(UUID actionId, String principal, Set<String> roles, String callerTenantId) {
        AiOperationalAction action = actions.get(actionId);
        if (action == null) {
            throw new IllegalArgumentException("Operational recommendation not found: " + actionId);
        }

        // 1. RBAC check
        assertOperatorOrAdmin(roles);

        // 2. Tenant check
        assertTenantAccess(action.tenantId(), callerTenantId, roles);

        // 3. Status check
        if (action.status() != AiActionStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Action is not in PENDING_APPROVAL state (current: " + action.status() + ")");
        }

        // 4. Expiration check
        if (Instant.now().isAfter(action.expiresAt())) {
            expireAction(action);
            throw new AiActionExpiredException("Action recommendation " + actionId + " has expired");
        }

        // 5. STALE STATE PROTECTION & EXECUTION
        String executionResult;
        try {
            executionResult = executeVerifiedAction(action, principal);
        } catch (StaleActionStateException ex) {
            auditService.record("AI_OPERATIONS_AGENT", principal, action.tenantId(), "ACTION_REJECTED_STALE",
                    action.targetId(), false, ex.getMessage());
            throw ex;
        }

        // 6. Update action record
        AiOperationalAction executed = AiOperationalAction.builder()
                .actionId(action.actionId())
                .tenantId(action.tenantId())
                .title(action.title())
                .reason(action.reason())
                .risk(action.risk())
                .actionType(action.actionType())
                .targetId(action.targetId())
                .evidence(action.evidence())
                .status(AiActionStatus.EXECUTED)
                .createdAt(action.createdAt())
                .expiresAt(action.expiresAt())
                .executedAt(Instant.now())
                .executedBy(principal)
                .executionResult(executionResult)
                .build();
        actions.put(actionId, executed);

        auditService.record("AI_OPERATIONS_AGENT", principal, action.tenantId(), action.actionType().name(),
                action.targetId(), true, executionResult);

        return executed;
    }

    public void rejectAction(UUID actionId, String principal, String reason) {
        AiOperationalAction action = actions.get(actionId);
        if (action == null) {
            throw new IllegalArgumentException("Operational recommendation not found: " + actionId);
        }

        AiOperationalAction rejected = AiOperationalAction.builder()
                .actionId(action.actionId())
                .tenantId(action.tenantId())
                .title(action.title())
                .reason(action.reason())
                .risk(action.risk())
                .actionType(action.actionType())
                .targetId(action.targetId())
                .evidence(action.evidence())
                .status(AiActionStatus.REJECTED)
                .createdAt(action.createdAt())
                .expiresAt(action.expiresAt())
                .executionResult("Rejected by operator: " + (reason != null ? reason : "No reason provided"))
                .build();
        actions.put(actionId, rejected);

        auditService.record("AI_OPERATIONS_AGENT", principal, action.tenantId(), "REJECT_ACTION",
                actionId.toString(), true, reason != null ? reason : "Operator rejected action");
    }

    private String executeVerifiedAction(AiOperationalAction action, String principal) {
        return switch (action.actionType()) {
            case RETRY_TASK -> {
                UUID taskId = parseUuid(action.targetId(), "Task ID");
                TaskSummaryDTO task = taskPort.getTask(taskId)
                        .orElseThrow(() -> new StaleActionStateException("Target task no longer exists: " + taskId));
                if (task.status() != TaskStatus.FAILED) {
                    throw new StaleActionStateException(String.format(
                            "Stale target state: Task %s is currently in state %s (expected FAILED)",
                            taskId, task.status()));
                }
                taskPort.retryTask(taskId, principal);
                yield "Task " + taskId + " successfully retried into QUEUED status";
            }
            case CANCEL_TASK -> {
                UUID taskId = parseUuid(action.targetId(), "Task ID");
                TaskSummaryDTO task = taskPort.getTask(taskId)
                        .orElseThrow(() -> new StaleActionStateException("Target task no longer exists: " + taskId));
                if (task.status() == TaskStatus.SUCCEEDED || task.status() == TaskStatus.FAILED || task.status() == TaskStatus.CANCELLED) {
                    throw new StaleActionStateException(String.format(
                            "Stale target state: Task %s is already terminal (%s)", taskId, task.status()));
                }
                taskPort.cancelTask(taskId, principal, action.reason());
                yield "Task " + taskId + " cancelled successfully";
            }
            case REPLAY_DLQ -> {
                UUID taskId = parseUuid(action.targetId(), "DLQ Task ID");
                if (dlqPort.inspectDlq(taskId).isEmpty()) {
                    throw new StaleActionStateException("Stale target state: Task " + taskId + " is no longer in Dead-Letter Queue");
                }
                dlqPort.replayDlq(taskId, principal);
                yield "DLQ task " + taskId + " replayed back to QUEUED";
            }
            case ARCHIVE_DLQ -> {
                UUID taskId = parseUuid(action.targetId(), "DLQ Task ID");
                if (dlqPort.inspectDlq(taskId).isEmpty()) {
                    throw new StaleActionStateException("Stale target state: Task " + taskId + " is no longer in Dead-Letter Queue");
                }
                dlqPort.archiveDlq(taskId, principal, action.reason());
                yield "DLQ task " + taskId + " archived successfully";
            }
            case CANCEL_WORKFLOW -> {
                UUID execId = parseUuid(action.targetId(), "Execution ID");
                var exec = workflowPort.getExecution(execId)
                        .orElseThrow(() -> new StaleActionStateException("Target execution no longer exists: " + execId));
                if (!"RUNNING".equalsIgnoreCase(exec.status()) && !"PENDING".equalsIgnoreCase(exec.status())) {
                    throw new StaleActionStateException("Stale target state: Workflow execution is already " + exec.status());
                }
                workflowPort.cancelExecution(execId, principal);
                yield "Workflow execution " + execId + " cancelled";
            }
            case INSPECT_WORKER -> "Worker inspection confirmed for telemetry review: " + action.targetId();
        };
    }

    private AiOperationalAction expireAction(AiOperationalAction action) {
        AiOperationalAction expired = AiOperationalAction.builder()
                .actionId(action.actionId())
                .tenantId(action.tenantId())
                .title(action.title())
                .reason(action.reason())
                .risk(action.risk())
                .actionType(action.actionType())
                .targetId(action.targetId())
                .evidence(action.evidence())
                .status(AiActionStatus.EXPIRED)
                .createdAt(action.createdAt())
                .expiresAt(action.expiresAt())
                .executionResult("Recommendation expired prior to approval")
                .build();
        actions.put(action.actionId(), expired);
        return expired;
    }

    private UUID parseUuid(String idStr, String label) {
        try {
            return UUID.fromString(idStr.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " must be a valid UUID: " + idStr);
        }
    }

    private void assertOperatorOrAdmin(Set<String> roles) {
        if (roles == null) {
            throw new SecurityException("Insufficient permissions: No roles present in security context");
        }
        boolean authorized = roles.contains("ROLE_OPERATOR") || roles.contains("ROLE_ADMIN")
                || roles.contains("OPERATOR") || roles.contains("ADMIN");
        if (!authorized) {
            throw new SecurityException("Access Denied: Action approval requires ROLE_OPERATOR or ROLE_ADMIN");
        }
    }

    private void assertTenantAccess(String targetTenantId, String callerTenantId, Set<String> roles) {
        if (targetTenantId == null || targetTenantId.isBlank()) {
            return;
        }
        boolean isAdmin = roles != null && (roles.contains("ROLE_ADMIN") || roles.contains("ADMIN"));
        if (!isAdmin && callerTenantId != null && !callerTenantId.equalsIgnoreCase(targetTenantId)) {
            throw new SecurityException(String.format(
                    "Tenant Isolation: Caller tenant [%s] cannot operate on resource owned by [%s]",
                    callerTenantId, targetTenantId));
        }
    }
}
