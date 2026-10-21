package com.chronoscore.coordinator.api.security;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.domain.TenantExecutionPolicy;
import com.chronoscore.common.domain.workflow.WorkflowExecution;
import com.chronoscore.common.enums.WorkflowStatus;
import com.chronoscore.coordinator.api.advice.GlobalExceptionHandler;
import com.chronoscore.coordinator.api.controller.DlqController;
import com.chronoscore.coordinator.api.controller.TaskController;
import com.chronoscore.coordinator.api.controller.TenantPolicyController;
import com.chronoscore.coordinator.api.controller.WorkflowController;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import com.chronoscore.coordinator.persistence.repository.TenantPolicyRepository;
import com.chronoscore.coordinator.persistence.service.TransactionalTaskService;
import com.chronoscore.coordinator.recovery.dlq.DlqService;
import com.chronoscore.coordinator.recovery.dlq.DlqTaskInspection;
import com.chronoscore.coordinator.scheduler.SchedulerProperties;
import com.chronoscore.coordinator.workflow.service.WorkflowExecutionService;
import com.chronoscore.coordinator.workflow.service.WorkflowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mandatory security test suite verifying Insecure Direct Object Reference (IDOR)
 * prevention across all domain resources (Tasks, Workflows, DLQ, Tenant Policies).
 * (Phase 10 Sections 35, 36, 37, 54).
 */
@WebMvcTest(controllers = {TaskController.class, WorkflowController.class, DlqController.class, TenantPolicyController.class})
@Import({SecurityConfig.class, JwtSecurityProperties.class, DevJwtTokenHelper.class, TenantSecurityContext.class, GlobalExceptionHandler.class})
class TenantIsolationIdorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DevJwtTokenHelper tokenHelper;

    @MockBean
    private TransactionalTaskService taskService;

    @MockBean
    private TaskRepository taskRepository;

    @MockBean
    private WorkflowService workflowService;

    @MockBean
    private WorkflowExecutionService executionService;

    @MockBean
    private DlqService dlqService;

    @MockBean
    private TenantPolicyRepository policyRepository;

    @MockBean
    private LeaderElectionService leaderElectionService;

    @MockBean
    private WorkerRegistry workerRegistry;

    @MockBean
    private SchedulerProperties schedulerProperties;

    // =========================================================================
    // 1. Task IDOR Protection
    // =========================================================================

    @Test
    @DisplayName("Tenant A user cannot read Tenant B's task (IDOR prevention -> 403 Forbidden)")
    void tenantACannotReadTenantBTask() throws Exception {
        String token = tokenHelper.generateToken("user-a", "tenant-a", List.of("OPERATOR"));
        UUID taskBId = UUID.randomUUID();

        Task taskB = Task.builder()
                .taskId(taskBId)
                .taskType("BILLING")
                .payload("{}")
                .tenantId("tenant-b")
                .build();

        when(taskRepository.findById(taskBId)).thenReturn(Optional.of(taskB));

        mockMvc.perform(get("/api/v1/tasks/" + taskBId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Tenant A user cannot cancel Tenant B's task (IDOR prevention -> 403 Forbidden)")
    void tenantACannotCancelTenantBTask() throws Exception {
        String token = tokenHelper.generateToken("user-a", "tenant-a", List.of("OPERATOR"));
        UUID taskBId = UUID.randomUUID();

        Task taskB = Task.builder()
                .taskId(taskBId)
                .taskType("BILLING")
                .payload("{}")
                .tenantId("tenant-b")
                .build();

        when(taskRepository.findById(taskBId)).thenReturn(Optional.of(taskB));

        mockMvc.perform(post("/api/v1/tasks/" + taskBId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // =========================================================================
    // 2. Workflow Execution IDOR Protection
    // =========================================================================

    @Test
    @DisplayName("Tenant A user cannot view Tenant B's workflow execution (IDOR prevention -> 403 Forbidden)")
    void tenantACannotViewTenantBWorkflowExecution() throws Exception {
        String token = tokenHelper.generateToken("user-a", "tenant-a", List.of("VIEWER"));
        UUID execBId = UUID.randomUUID();

        WorkflowExecution execB = WorkflowExecution.builder()
                .executionId(execBId)
                .workflowId(UUID.randomUUID())
                .versionId(UUID.randomUUID())
                .version(1)
                .tenantId("tenant-b")
                .status(WorkflowStatus.RUNNING)
                .createdAt(Instant.now())
                .build();

        when(executionService.getExecution(execBId)).thenReturn(Optional.of(execB));

        mockMvc.perform(get("/api/v1/workflow-executions/" + execBId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Tenant A user cannot cancel Tenant B's workflow execution (IDOR prevention -> 403 Forbidden)")
    void tenantACannotCancelTenantBWorkflowExecution() throws Exception {
        String token = tokenHelper.generateToken("user-a", "tenant-a", List.of("OPERATOR"));
        UUID execBId = UUID.randomUUID();

        WorkflowExecution execB = WorkflowExecution.builder()
                .executionId(execBId)
                .workflowId(UUID.randomUUID())
                .versionId(UUID.randomUUID())
                .version(1)
                .tenantId("tenant-b")
                .status(WorkflowStatus.RUNNING)
                .createdAt(Instant.now())
                .build();

        when(executionService.getExecution(execBId)).thenReturn(Optional.of(execB));

        mockMvc.perform(post("/api/v1/workflow-executions/" + execBId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // =========================================================================
    // 3. DLQ IDOR Protection
    // =========================================================================

    @Test
    @DisplayName("Tenant A user cannot inspect Tenant B's DLQ item (IDOR prevention -> 403 Forbidden)")
    void tenantACannotInspectTenantBDlqTask() throws Exception {
        String token = tokenHelper.generateToken("user-a", "tenant-a", List.of("VIEWER"));
        UUID taskBId = UUID.randomUUID();

        Task taskB = Task.builder()
                .taskId(taskBId)
                .taskType("BILLING")
                .payload("{}")
                .tenantId("tenant-b")
                .build();

        DlqTaskInspection inspection = new DlqTaskInspection(taskB, Collections.emptyList(), Collections.emptyList());
        when(dlqService.inspectTask(taskBId)).thenReturn(Optional.of(inspection));

        mockMvc.perform(get("/api/v1/dlq/" + taskBId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Tenant A user cannot replay Tenant B's DLQ item (IDOR prevention -> 403 Forbidden)")
    void tenantACannotReplayTenantBDlqTask() throws Exception {
        String token = tokenHelper.generateToken("user-a", "tenant-a", List.of("OPERATOR"));
        UUID taskBId = UUID.randomUUID();

        Task taskB = Task.builder()
                .taskId(taskBId)
                .taskType("BILLING")
                .payload("{}")
                .tenantId("tenant-b")
                .build();

        DlqTaskInspection inspection = new DlqTaskInspection(taskB, Collections.emptyList(), Collections.emptyList());
        when(dlqService.inspectTask(taskBId)).thenReturn(Optional.of(inspection));

        mockMvc.perform(post("/api/v1/dlq/" + taskBId + "/replay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // =========================================================================
    // 4. Tenant Policy IDOR Protection
    // =========================================================================

    @Test
    @DisplayName("Tenant A user cannot view Tenant B's policy (IDOR prevention -> 403 Forbidden)")
    void tenantACannotViewTenantBPolicy() throws Exception {
        String token = tokenHelper.generateToken("user-a", "tenant-a", List.of("VIEWER"));

        mockMvc.perform(get("/api/v1/tenants/tenant-b/policy")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // =========================================================================
    // 5. Cross-Tenant Administrative Privileges
    // =========================================================================

    @Test
    @DisplayName("ADMIN role can access cross-tenant tasks and executions without restriction")
    void adminCanAccessCrossTenantResources() throws Exception {
        String adminToken = tokenHelper.generateToken("admin-super", "system", List.of("ADMIN"));
        UUID taskBId = UUID.randomUUID();

        Task taskB = Task.builder()
                .taskId(taskBId)
                .taskType("BILLING")
                .payload("{}")
                .tenantId("tenant-b")
                .build();

        when(taskRepository.findById(taskBId)).thenReturn(Optional.of(taskB));

        mockMvc.perform(get("/api/v1/tasks/" + taskBId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskBId.toString()))
                .andExpect(jsonPath("$.tenantId").value("tenant-b"));
    }
}
