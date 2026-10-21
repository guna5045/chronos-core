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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ClusterController.class, DevAuthController.class})
@Import({SecurityConfig.class, JwtSecurityProperties.class, DevJwtTokenHelper.class, TenantSecurityContext.class, GlobalExceptionHandler.class})
class SecurityAuthTest {

    @Autowired
    private MockMvc mockMvc;

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
    @DisplayName("Unauthenticated request returns 401 Unauthorized with standard error response")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/cluster"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.path").value("/api/v1/cluster"));
    }

    @Test
    @DisplayName("Authenticated request with valid JWT token succeeds with 200 OK")
    void validJwtTokenSucceeds() throws Exception {
        String token = tokenHelper.generateToken("user-1", "tenant-alpha", List.of("VIEWER"));

        mockMvc.perform(get("/api/v1/cluster")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaderId").value("coord-1"))
                .andExpect(jsonPath("$.isLeader").value(true))
                .andExpect(jsonPath("$.fencingToken").value(10));
    }

    @Test
    @DisplayName("Expired JWT token returns 401 Unauthorized")
    void expiredJwtTokenReturns401() throws Exception {
        // -3600 seconds = expired 1 hour ago
        String expiredToken = tokenHelper.generateToken("user-1", "tenant-alpha", List.of("VIEWER"), -3600);

        mockMvc.perform(get("/api/v1/cluster")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("Tampered JWT signature returns 401 Unauthorized")
    void tamperedTokenSignatureReturns401() throws Exception {
        String validToken = tokenHelper.generateToken("user-1", "tenant-alpha", List.of("VIEWER"));
        String tamperedToken = validToken.substring(0, validToken.length() - 5) + "abcde";

        mockMvc.perform(get("/api/v1/cluster")
                        .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("Dev auth token endpoint generates valid token that authenticates")
    void devTokenEndpointGeneratesUsableToken() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-user\",\"tenantId\":\"tenant-beta\",\"roles\":[\"OPERATOR\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();

        // Extract token and use it
        String token = com.jayway.jsonpath.JsonPath.read(response, "$.accessToken");

        mockMvc.perform(get("/api/v1/cluster")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
