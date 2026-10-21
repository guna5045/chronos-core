package com.chronoscore.coordinator.api.security;

import com.chronoscore.common.domain.Task;
import com.chronoscore.common.domain.TenantExecutionPolicy;
import com.chronoscore.common.domain.workflow.WorkflowDefinition;
import com.chronoscore.common.enums.TaskPriority;
import com.chronoscore.coordinator.api.advice.GlobalExceptionHandler;
import com.chronoscore.coordinator.api.controller.DlqController;
import com.chronoscore.coordinator.api.controller.TaskController;
import com.chronoscore.coordinator.api.controller.TenantPolicyController;
import com.chronoscore.coordinator.api.controller.WorkflowController;
import com.chronoscore.coordinator.api.dto.request.CreateTaskRequest;
import com.chronoscore.coordinator.api.dto.request.CreateWorkflowRequest;
import com.chronoscore.coordinator.api.dto.request.UpdateTenantPolicyRequest;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {TaskController.class, WorkflowController.class, DlqController.class, TenantPolicyController.class})
@Import({SecurityConfig.class, JwtSecurityProperties.class, DevJwtTokenHelper.class, TenantSecurityContext.class, GlobalExceptionHandler.class})
class RbacAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DevJwtTokenHelper tokenHelper;

    @Autowired
    private ObjectMapper objectMapper;

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
    // 1. ROLE_VIEWER Tests
    // =========================================================================

    @Test
    @DisplayName("VIEWER role can read tasks")
    void viewerCanReadTasks() throws Exception {
        String token = tokenHelper.generateToken("viewer-1", "tenant-alpha", List.of("VIEWER"));

        when(taskRepository.countTasksWithFilter(any(), any(), any(), any())).thenReturn(0);
        when(taskRepository.findTasksWithFilter(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("VIEWER role cannot submit tasks (403 Forbidden)")
    void viewerCannotSubmitTasks() throws Exception {
        String token = tokenHelper.generateToken("viewer-1", "tenant-alpha", List.of("VIEWER"));
        CreateTaskRequest req = new CreateTaskRequest("PAYMENT", "{}", TaskPriority.NORMAL, null, null, null, null, null, null, null);

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("VIEWER role cannot cancel tasks (403 Forbidden)")
    void viewerCannotCancelTasks() throws Exception {
        String token = tokenHelper.generateToken("viewer-1", "tenant-alpha", List.of("VIEWER"));
        UUID taskId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("VIEWER role cannot replay DLQ (403 Forbidden)")
    void viewerCannotReplayDlq() throws Exception {
        String token = tokenHelper.generateToken("viewer-1", "tenant-alpha", List.of("VIEWER"));
        UUID taskId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/dlq/" + taskId + "/replay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("VIEWER role cannot update tenant policy (403 Forbidden)")
    void viewerCannotUpdateTenantPolicy() throws Exception {
        String token = tokenHelper.generateToken("viewer-1", "tenant-alpha", List.of("VIEWER"));
        UpdateTenantPolicyRequest req = new UpdateTenantPolicyRequest(10, 100, 20.0, 40, 5);

        mockMvc.perform(put("/api/v1/tenants/tenant-alpha/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // =========================================================================
    // 2. ROLE_OPERATOR Tests
    // =========================================================================

    @Test
    @DisplayName("OPERATOR role can submit tasks (201 Created)")
    void operatorCanSubmitTasks() throws Exception {
        String token = tokenHelper.generateToken("operator-1", "tenant-alpha", List.of("OPERATOR"));
        CreateTaskRequest req = new CreateTaskRequest("PAYMENT", "{}", TaskPriority.NORMAL, null, null, null, null, null, null, null);

        Task mockTask = Task.builder()
                .taskId(UUID.randomUUID())
                .taskType("PAYMENT")
                .payload("{}")
                .tenantId("tenant-alpha")
                .build();

        when(taskService.submitTask(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskType").value("PAYMENT"))
                .andExpect(jsonPath("$.tenantId").value("tenant-alpha"));
    }

    @Test
    @DisplayName("OPERATOR role can retry tasks (200 OK)")
    void operatorCanRetryTasks() throws Exception {
        String token = tokenHelper.generateToken("operator-1", "tenant-alpha", List.of("OPERATOR"));
        UUID taskId = UUID.randomUUID();

        Task mockTask = Task.builder()
                .taskId(taskId)
                .taskType("PAYMENT")
                .payload("{}")
                .tenantId("tenant-alpha")
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(mockTask));
        when(dlqService.replayTask(eq(taskId), anyString())).thenReturn(mockTask);

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/retry")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("OPERATOR role can replay DLQ task (200 OK)")
    void operatorCanReplayDlq() throws Exception {
        String token = tokenHelper.generateToken("operator-1", "tenant-alpha", List.of("OPERATOR"));
        UUID taskId = UUID.randomUUID();

        Task mockTask = Task.builder()
                .taskId(taskId)
                .taskType("PAYMENT")
                .payload("{}")
                .tenantId("tenant-alpha")
                .build();

        DlqTaskInspection details = new DlqTaskInspection(mockTask, Collections.emptyList(), Collections.emptyList());
        when(dlqService.inspectTask(taskId)).thenReturn(Optional.of(details));
        when(dlqService.replayTask(eq(taskId), anyString())).thenReturn(mockTask);

        mockMvc.perform(post("/api/v1/dlq/" + taskId + "/replay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("OPERATOR role cannot create workflows (ADMIN only, 403 Forbidden)")
    void operatorCannotCreateWorkflows() throws Exception {
        String token = tokenHelper.generateToken("operator-1", "tenant-alpha", List.of("OPERATOR"));
        CreateWorkflowRequest req = new CreateWorkflowRequest("Order Flow", "Description");

        mockMvc.perform(post("/api/v1/workflows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("OPERATOR role cannot update tenant policy (ADMIN only, 403 Forbidden)")
    void operatorCannotUpdateTenantPolicy() throws Exception {
        String token = tokenHelper.generateToken("operator-1", "tenant-alpha", List.of("OPERATOR"));
        UpdateTenantPolicyRequest req = new UpdateTenantPolicyRequest(10, 100, 20.0, 40, 5);

        mockMvc.perform(put("/api/v1/tenants/tenant-alpha/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // =========================================================================
    // 3. ROLE_ADMIN Tests
    // =========================================================================

    @Test
    @DisplayName("ADMIN role can create workflows (201 Created)")
    void adminCanCreateWorkflows() throws Exception {
        String token = tokenHelper.generateToken("admin-1", "tenant-alpha", List.of("ADMIN"));
        CreateWorkflowRequest req = new CreateWorkflowRequest("Order Flow", "Description");

        UUID workflowId = UUID.randomUUID();
        WorkflowDefinition def = WorkflowDefinition.builder()
                .workflowId(workflowId)
                .name("Order Flow")
                .description("Description")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(workflowService.createWorkflow("Order Flow", "Description")).thenReturn(def);

        mockMvc.perform(post("/api/v1/workflows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Order Flow"));
    }

    @Test
    @DisplayName("ADMIN role can update tenant policy (200 OK)")
    void adminCanUpdateTenantPolicy() throws Exception {
        String token = tokenHelper.generateToken("admin-1", "tenant-alpha", List.of("ADMIN"));
        UpdateTenantPolicyRequest req = new UpdateTenantPolicyRequest(15, 150, 30.0, 60, 10);

        TenantExecutionPolicy policy = TenantExecutionPolicy.builder()
                .tenantId("tenant-alpha")
                .maxConcurrentTasks(15)
                .maxQueuedTasks(150)
                .tasksPerSecond(30.0)
                .burstCapacity(60)
                .maxWorkflowExecutions(10)
                .updatedAt(Instant.now())
                .build();

        when(policyRepository.getEffectivePolicy("tenant-alpha")).thenReturn(policy);
        when(policyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(policyRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(put("/api/v1/tenants/tenant-alpha/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-alpha"))
                .andExpect(jsonPath("$.maxConcurrentTasks").value(15));
    }
}
