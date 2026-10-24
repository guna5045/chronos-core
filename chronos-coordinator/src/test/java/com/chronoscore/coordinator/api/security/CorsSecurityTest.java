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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ClusterController.class, DevAuthController.class})
@Import({SecurityConfig.class, JwtSecurityProperties.class, DevJwtTokenHelper.class, TenantSecurityContext.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "chronos.security.cors-allowed-origins=https://chronos-core.netlify.app,http://localhost:5173"
})
class CorsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

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
    @DisplayName("CORS preflight with content-type, authorization, and x-request-id is accepted for Netlify origin")
    void corsPreflightWithXRequestIdAcceptedForNetlifyOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/cluster")
                        .header("Origin", "https://chronos-core.netlify.app")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "content-type, authorization, x-request-id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://chronos-core.netlify.app"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,HEAD"))
                .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("x-request-id")));
    }

    @Test
    @DisplayName("CORS preflight for auth token endpoint with POST method and x-request-id is accepted")
    void corsPreflightForAuthTokenWithPostAndXRequestIdAccepted() throws Exception {
        mockMvc.perform(options("/api/v1/auth/token")
                        .header("Origin", "https://chronos-core.netlify.app")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type, authorization, x-request-id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://chronos-core.netlify.app"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,HEAD"))
                .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("x-request-id")));
    }

    @Test
    @DisplayName("CORS preflight from untrusted origin is rejected with 403 Forbidden")
    void corsPreflightFromUntrustedOriginRejected() throws Exception {
        mockMvc.perform(options("/api/v1/cluster")
                        .header("Origin", "https://malicious-site.example.com")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "content-type, authorization, x-request-id"))
                .andExpect(status().isForbidden());
    }
}
