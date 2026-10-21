package com.chronoscore.coordinator.api.controller;

import com.chronoscore.common.domain.TenantExecutionPolicy;
import com.chronoscore.coordinator.api.advice.GlobalExceptionHandler;
import com.chronoscore.coordinator.api.dto.request.UpdateTenantPolicyRequest;
import com.chronoscore.coordinator.api.security.DevJwtTokenHelper;
import com.chronoscore.coordinator.api.security.JwtSecurityProperties;
import com.chronoscore.coordinator.api.security.SecurityConfig;
import com.chronoscore.coordinator.api.security.TenantSecurityContext;
import com.chronoscore.coordinator.persistence.repository.TenantPolicyRepository;
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
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TenantPolicyController.class)
@Import({SecurityConfig.class, JwtSecurityProperties.class, DevJwtTokenHelper.class, TenantSecurityContext.class, GlobalExceptionHandler.class})
class TenantPolicyControllerRestTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DevJwtTokenHelper tokenHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TenantPolicyRepository policyRepository;

    private String getViewerToken(String tenantId) {
        return tokenHelper.generateToken("viewer-user", tenantId, List.of("VIEWER"));
    }

    private String getAdminToken() {
        return tokenHelper.generateToken("admin-user", "system", List.of("ADMIN"));
    }

    @Test
    @DisplayName("GET /api/v1/tenants/{tenantId}/policy returns effective policy (200 OK)")
    void getTenantPolicySuccess() throws Exception {
        String token = getViewerToken("tenant-alpha");
        TenantExecutionPolicy policy = TenantExecutionPolicy.builder()
                .tenantId("tenant-alpha")
                .maxConcurrentTasks(25)
                .maxQueuedTasks(250)
                .tasksPerSecond(50.0)
                .burstCapacity(100)
                .maxWorkflowExecutions(15)
                .updatedAt(Instant.now())
                .build();

        when(policyRepository.getEffectivePolicy("tenant-alpha")).thenReturn(policy);

        mockMvc.perform(get("/api/v1/tenants/tenant-alpha/policy")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-alpha"))
                .andExpect(jsonPath("$.maxConcurrentTasks").value(25))
                .andExpect(jsonPath("$.maxQueuedTasks").value(250))
                .andExpect(jsonPath("$.tasksPerSecond").value(50.0))
                .andExpect(jsonPath("$.burstCapacity").value(100))
                .andExpect(jsonPath("$.maxWorkflowExecutions").value(15));
    }

    @Test
    @DisplayName("PUT /api/v1/tenants/{tenantId}/policy updates policy as ADMIN (200 OK)")
    void updateTenantPolicySuccess() throws Exception {
        String token = getAdminToken();
        TenantExecutionPolicy existing = TenantExecutionPolicy.defaultPolicy("tenant-alpha");

        when(policyRepository.getEffectivePolicy("tenant-alpha")).thenReturn(existing);
        when(policyRepository.findByTenantId("tenant-alpha")).thenReturn(Optional.of(existing));
        when(policyRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateTenantPolicyRequest request = new UpdateTenantPolicyRequest(30, 300, 60.0, 120, 20);

        mockMvc.perform(put("/api/v1/tenants/tenant-alpha/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-alpha"))
                .andExpect(jsonPath("$.maxConcurrentTasks").value(30))
                .andExpect(jsonPath("$.maxQueuedTasks").value(300))
                .andExpect(jsonPath("$.tasksPerSecond").value(60.0))
                .andExpect(jsonPath("$.burstCapacity").value(120))
                .andExpect(jsonPath("$.maxWorkflowExecutions").value(20));
    }

    @Test
    @DisplayName("PUT /api/v1/tenants/{tenantId}/policy with negative values returns 400 Bad Request")
    void updateTenantPolicyNegativeValidationFailure() throws Exception {
        String token = getAdminToken();
        UpdateTenantPolicyRequest request = new UpdateTenantPolicyRequest(-5, -100, -10.0, -20, -1);

        mockMvc.perform(put("/api/v1/tenants/tenant-alpha/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    @DisplayName("PUT /api/v1/tenants/{tenantId}/policy rejected for non-admin role (403 Forbidden)")
    void updateTenantPolicyNonAdminForbidden() throws Exception {
        String token = tokenHelper.generateToken("op-user", "tenant-alpha", List.of("OPERATOR"));
        UpdateTenantPolicyRequest request = new UpdateTenantPolicyRequest(30, 300, 60.0, 120, 20);

        mockMvc.perform(put("/api/v1/tenants/tenant-alpha/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
