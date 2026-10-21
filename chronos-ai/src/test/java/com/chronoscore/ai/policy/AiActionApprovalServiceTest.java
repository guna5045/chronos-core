package com.chronoscore.ai.policy;

import com.chronoscore.ai.audit.AiAuditService;
import com.chronoscore.ai.dto.AiActionRisk;
import com.chronoscore.ai.dto.AiActionStatus;
import com.chronoscore.ai.dto.AiActionType;
import com.chronoscore.ai.dto.AiOperationalAction;
import com.chronoscore.ai.dto.AiProposalStatus;
import com.chronoscore.ai.dto.AiWorkflowProposal;
import com.chronoscore.ai.ports.AiDlqOperationsPort;
import com.chronoscore.ai.ports.AiTaskOperationsPort;
import com.chronoscore.ai.ports.AiWorkflowOperationsPort;
import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.dto.TaskSummaryDTO;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiActionApprovalServiceTest {

    private AiTaskOperationsPort taskPort;
    private AiDlqOperationsPort dlqPort;
    private AiWorkflowOperationsPort workflowPort;
    private AiAuditService auditService;
    private AiActionApprovalService approvalService;

    @BeforeEach
    void setUp() {
        taskPort = mock(AiTaskOperationsPort.class);
        dlqPort = mock(AiDlqOperationsPort.class);
        workflowPort = mock(AiWorkflowOperationsPort.class);
        auditService = new AiAuditService();
        approvalService = new AiActionApprovalService(taskPort, dlqPort, workflowPort, auditService);
    }

    @Test
    @DisplayName("Successfully approves and executes a valid task retry recommendation")
    void shouldApproveAndExecuteValidAction() {
        UUID taskId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        // Target task is currently FAILED
        TaskSummaryDTO failedTask = new TaskSummaryDTO(
                taskId, "TEST_TASK", TaskPriority.NORMAL, TaskStatus.FAILED,
                "tenant-alpha", Instant.now(), 3, 3, 1L
        );
        when(taskPort.getTask(taskId)).thenReturn(Optional.of(failedTask));

        AiOperationalAction action = AiOperationalAction.builder()
                .actionId(actionId)
                .tenantId("tenant-alpha")
                .title("Retry failed task")
                .reason("Transient failure")
                .risk(AiActionRisk.LOW)
                .actionType(AiActionType.RETRY_TASK)
                .targetId(taskId.toString())
                .status(AiActionStatus.PENDING_APPROVAL)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        approvalService.registerAction(action);

        AiOperationalAction executed = approvalService.approveAction(
                actionId, "operator-1", Set.of("ROLE_OPERATOR"), "tenant-alpha"
        );

        assertNotNull(executed);
        assertEquals(AiActionStatus.EXECUTED, executed.status());
        assertEquals("operator-1", executed.executedBy());
        verify(taskPort).retryTask(taskId, "operator-1");
    }

    @Test
    @DisplayName("STALE STATE PROTECTION: Rejects action approval if target task is no longer in FAILED state")
    void shouldRejectActionIfStaleTargetState() {
        UUID taskId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        // Target task succeeded in the background before operator clicked approve!
        TaskSummaryDTO succeededTask = new TaskSummaryDTO(
                taskId, "TEST_TASK", TaskPriority.NORMAL, TaskStatus.SUCCEEDED,
                "tenant-alpha", Instant.now(), 1, 3, 2L
        );
        when(taskPort.getTask(taskId)).thenReturn(Optional.of(succeededTask));

        AiOperationalAction action = AiOperationalAction.builder()
                .actionId(actionId)
                .tenantId("tenant-alpha")
                .title("Retry failed task")
                .reason("Transient failure")
                .actionType(AiActionType.RETRY_TASK)
                .targetId(taskId.toString())
                .status(AiActionStatus.PENDING_APPROVAL)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        approvalService.registerAction(action);

        StaleActionStateException ex = assertThrows(StaleActionStateException.class, () ->
                approvalService.approveAction(actionId, "operator-1", Set.of("ROLE_OPERATOR"), "tenant-alpha")
        );
        assertTrue(ex.getMessage().contains("expected FAILED"));
        verify(taskPort, never()).retryTask(any(), any());
    }

    @Test
    @DisplayName("Rejects action approval if recommendation has expired")
    void shouldRejectActionIfExpired() {
        UUID actionId = UUID.randomUUID();

        AiOperationalAction action = AiOperationalAction.builder()
                .actionId(actionId)
                .tenantId("tenant-alpha")
                .title("Retry failed task")
                .actionType(AiActionType.RETRY_TASK)
                .targetId(UUID.randomUUID().toString())
                .status(AiActionStatus.PENDING_APPROVAL)
                .expiresAt(Instant.now().minusSeconds(10)) // Already expired!
                .build();
        approvalService.registerAction(action);

        AiActionExpiredException ex = assertThrows(AiActionExpiredException.class, () ->
                approvalService.approveAction(actionId, "operator-1", Set.of("ROLE_OPERATOR"), "tenant-alpha")
        );
        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    @DisplayName("Tenant Isolation: Non-admin user cannot approve action belonging to different tenant")
    void shouldEnforceTenantIsolationOnApproval() {
        UUID actionId = UUID.randomUUID();

        AiOperationalAction action = AiOperationalAction.builder()
                .actionId(actionId)
                .tenantId("tenant-alpha")
                .title("Retry task")
                .actionType(AiActionType.RETRY_TASK)
                .targetId(UUID.randomUUID().toString())
                .status(AiActionStatus.PENDING_APPROVAL)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        approvalService.registerAction(action);

        SecurityException ex = assertThrows(SecurityException.class, () ->
                approvalService.approveAction(actionId, "operator-beta", Set.of("ROLE_OPERATOR"), "tenant-beta")
        );
        assertTrue(ex.getMessage().contains("Tenant Isolation"));
    }

    @Test
    @DisplayName("RBAC: VIEWER role cannot approve operational recommendations")
    void shouldEnforceRbacOnApproval() {
        UUID actionId = UUID.randomUUID();

        AiOperationalAction action = AiOperationalAction.builder()
                .actionId(actionId)
                .tenantId("tenant-alpha")
                .title("Retry task")
                .actionType(AiActionType.RETRY_TASK)
                .targetId(UUID.randomUUID().toString())
                .status(AiActionStatus.PENDING_APPROVAL)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        approvalService.registerAction(action);

        SecurityException ex = assertThrows(SecurityException.class, () ->
                approvalService.approveAction(actionId, "viewer-1", Set.of("ROLE_VIEWER"), "tenant-alpha")
        );
        assertTrue(ex.getMessage().contains("Access Denied"));
    }

    @Test
    @DisplayName("Approves workflow proposal and materializes draft version via WorkflowOperationsPort")
    void shouldApproveWorkflowProposalAndMaterialize() {
        UUID proposalId = UUID.randomUUID();
        UUID createdWfId = UUID.randomUUID();

        List<WorkflowNodeDefinition> nodes = List.of(
                WorkflowNodeDefinition.builder().nodeId("step-1").taskType("INGEST").build(),
                WorkflowNodeDefinition.builder().nodeId("step-2").taskType("PROCESS").build()
        );
        List<WorkflowEdge> edges = List.of(WorkflowEdge.of("step-1", "step-2"));

        AiWorkflowProposal proposal = AiWorkflowProposal.builder()
                .proposalId(proposalId)
                .tenantId("tenant-alpha")
                .workflowName("pipeline-v1")
                .nodes(nodes)
                .edges(edges)
                .valid(true)
                .status(AiProposalStatus.PENDING_REVIEW)
                .expiresAt(Instant.now().plusSeconds(1800))
                .build();
        approvalService.registerProposal(proposal);

        when(workflowPort.createWorkflow(eq("pipeline-v1"), any(), eq(nodes), eq(edges))).thenReturn(
                new AiWorkflowOperationsPort.WorkflowCreatedResult(createdWfId, "pipeline-v1", 1, "DRAFT")
        );

        AiWorkflowOperationsPort.WorkflowCreatedResult result = approvalService.approveWorkflow(
                proposalId, "admin-1", Set.of("ROLE_ADMIN"), "tenant-alpha"
        );

        assertNotNull(result);
        assertEquals(createdWfId, result.workflowId());
        assertEquals("pipeline-v1", result.name());
        assertEquals(1, result.version());

        AiWorkflowProposal updated = approvalService.getProposal(proposalId).orElseThrow();
        assertEquals(AiProposalStatus.APPROVED, updated.status());
    }

    @Test
    @DisplayName("Rejects workflow proposal updating status to REJECTED")
    void shouldRejectWorkflowProposal() {
        UUID proposalId = UUID.randomUUID();

        AiWorkflowProposal proposal = AiWorkflowProposal.builder()
                .proposalId(proposalId)
                .tenantId("tenant-alpha")
                .workflowName("rejected-wf")
                .status(AiProposalStatus.PENDING_REVIEW)
                .build();
        approvalService.registerProposal(proposal);

        approvalService.rejectWorkflow(proposalId, "operator-1", "Design disapproved");

        AiWorkflowProposal updated = approvalService.getProposal(proposalId).orElseThrow();
        assertEquals(AiProposalStatus.REJECTED, updated.status());
    }
}
