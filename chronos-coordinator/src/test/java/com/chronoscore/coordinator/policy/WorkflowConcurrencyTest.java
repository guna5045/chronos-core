package com.chronoscore.coordinator.policy;

import com.chronoscore.common.domain.TenantExecutionPolicy;
import com.chronoscore.common.domain.workflow.WorkflowEdge;
import com.chronoscore.common.domain.workflow.WorkflowExecution;
import com.chronoscore.common.domain.workflow.WorkflowNodeDefinition;
import com.chronoscore.common.domain.workflow.WorkflowVersion;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.common.enums.WorkflowStatus;
import com.chronoscore.common.enums.WorkflowVersionStatus;
import com.chronoscore.coordinator.persistence.repository.OutboxRepository;
import com.chronoscore.coordinator.persistence.repository.TenantPolicyRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import com.chronoscore.coordinator.workflow.repository.WorkflowAuditRepository;
import com.chronoscore.coordinator.workflow.repository.WorkflowExecutionRepository;
import com.chronoscore.coordinator.workflow.repository.WorkflowRepository;
import com.chronoscore.coordinator.workflow.service.WorkflowExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Test suite verifying workflow-level concurrency gating (maxWorkflowExecutions quota per tenant),
 * PENDING execution placement, and automatic resumption (Phase 09 Section 21, 22).
 */
@ExtendWith(MockitoExtension.class)
class WorkflowConcurrencyTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @Mock
    private WorkflowAuditRepository auditRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private TransactionalTaskService taskService;

    @Mock
    private TenantPolicyRepository policyRepository;

    private WorkflowExecutionService executionService;

    private final UUID workflowId = UUID.randomUUID();
    private final String tenantId = "tenant-wf-quota";

    @BeforeEach
    void setUp() {
        executionService = new WorkflowExecutionService(
                workflowRepository,
                executionRepository,
                auditRepository,
                outboxRepository,
                taskService,
                null,
                null,
                null,
                policyRepository
        );

        WorkflowNodeDefinition rootNode = WorkflowNodeDefinition.builder()
                .nodeId("extract")
                .taskType("EXTRACT")
                .priority(TaskPriority.NORMAL)
                .build();

        WorkflowVersion version = WorkflowVersion.builder()
                .versionId(UUID.randomUUID())
                .workflowId(workflowId)
                .version(1)
                .status(WorkflowVersionStatus.PUBLISHED)
                .nodes(List.of(rootNode))
                .edges(Collections.emptyList())
                .build();

        when(workflowRepository.findLatestPublishedVersion(workflowId)).thenReturn(Optional.of(version));
        when(executionRepository.saveExecution(any(WorkflowExecution.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Workflow Concurrency: Within quota -> execution starts as RUNNING")
    void testWorkflowExecutionWithinQuota() {
        TenantExecutionPolicy policy = TenantExecutionPolicy.builder()
                .tenantId(tenantId)
                .maxWorkflowExecutions(5)
                .build();

        when(policyRepository.getEffectivePolicy(tenantId)).thenReturn(policy);
        when(executionRepository.countActiveExecutionsByTenant(tenantId)).thenReturn(2);

        WorkflowExecution execution = executionService.startExecution(workflowId, null, tenantId);

        assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
    }

    @Test
    @DisplayName("Workflow Concurrency: Quota exceeded -> execution placed in PENDING state")
    void testWorkflowExecutionExceedingQuotaGatedAsPending() {
        TenantExecutionPolicy policy = TenantExecutionPolicy.builder()
                .tenantId(tenantId)
                .maxWorkflowExecutions(2)
                .build();

        when(policyRepository.getEffectivePolicy(tenantId)).thenReturn(policy);
        // Tenant already has 2 active running executions
        when(executionRepository.countActiveExecutionsByTenant(tenantId)).thenReturn(2);

        WorkflowExecution execution = executionService.startExecution(workflowId, null, tenantId);

        // Crucial invariant: 3rd execution remains PENDING
        assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.PENDING);
    }
}
