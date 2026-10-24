package com.chronoscore.coordinator.api.security;

import com.chronoscore.coordinator.api.advice.GlobalExceptionHandler;
import com.chronoscore.coordinator.api.controller.ClusterController;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.coordination.model.LeaderIdentity;
import com.chronoscore.coordinator.scheduler.SchedulerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hardening verification test proving that DevAuthController is strictly excluded
 * and cannot issue tokens when chronos.security.dev-auth.enabled=false (as in production profile).
 */
@WebMvcTest(controllers = {ClusterController.class, DevAuthController.class})
@Import({SecurityConfig.class, JwtSecurityProperties.class, DevJwtTokenHelper.class, TenantSecurityContext.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "chronos.security.dev-auth.enabled=false"
})
class DevAuthProductionHardeningTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DevJwtTokenHelper tokenHelper;

    @MockBean
    private LeaderElectionService leaderElectionService;

    @MockBean
    private WorkerRegistry workerRegistry;

    @MockBean
    private SchedulerProperties schedulerProperties;

    @BeforeEach
    void setUp() {
        when(leaderElectionService.isLeader()).thenReturn(true);
        when(leaderElectionService.getLeadershipIdentity()).thenReturn(
                LeaderIdentity.leader("coord-1", 10L, Instant.now(), Instant.now().plusSeconds(60)));
        when(workerRegistry.getAllWorkers()).thenReturn(Collections.emptyList());
        when(workerRegistry.getActiveWorkers()).thenReturn(Collections.emptyList());
        when(schedulerProperties.isEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("DevAuthController bean is NOT loaded into ApplicationContext when dev-auth is disabled")
    void devAuthControllerBeanNotLoadedWhenDevAuthDisabled() {
        boolean containsBean = applicationContext.containsBean("devAuthController");
        assertThat(containsBean).isFalse();
    }

    @Test
    @DisplayName("POST /api/v1/auth/token returns 404 Not Found when dev-auth is disabled in production")
    void devAuthEndpointReturns404WhenDisabled() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"attacker\",\"tenantId\":\"tenant-beta\",\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Normal authenticated APIs continue to work with valid external/OIDC JWT tokens")
    void normalAuthenticatedApisContinueWorking() throws Exception {
        String token = tokenHelper.generateToken("ops-user", "tenant-alpha", List.of("VIEWER"));

        mockMvc.perform(get("/api/v1/cluster")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaderId").value("coord-1"))
                .andExpect(jsonPath("$.isLeader").value(true));
    }

    @Test
    @DisplayName("Unauthenticated requests to normal APIs still return 401 Unauthorized")
    void unauthenticatedRequestsStillReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/cluster"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
