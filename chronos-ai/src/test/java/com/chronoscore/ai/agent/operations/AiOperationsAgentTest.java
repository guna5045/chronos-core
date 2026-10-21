package com.chronoscore.ai.agent.operations;

import com.chronoscore.ai.configuration.AiProperties;
import com.chronoscore.ai.dto.AiActionRisk;
import com.chronoscore.ai.dto.AiActionType;
import com.chronoscore.ai.dto.AiIncidentSeverity;
import com.chronoscore.ai.dto.IncidentAnalysisRequest;
import com.chronoscore.ai.dto.IncidentAnalysisResponse;
import com.chronoscore.ai.dto.IncidentScope;
import com.chronoscore.ai.ports.AiClusterOperationsPort;
import com.chronoscore.ai.ports.AiDlqOperationsPort;
import com.chronoscore.ai.ports.AiTaskOperationsPort;
import com.chronoscore.ai.ports.AiTenantOperationsPort;
import com.chronoscore.ai.ports.AiWorkerOperationsPort;
import com.chronoscore.ai.ports.AiWorkflowOperationsPort;
import com.chronoscore.ai.provider.StubAiProvider;
import com.chronoscore.ai.tools.AiToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiOperationsAgentTest {

    private StubAiProvider stubProvider;
    private AiToolRegistry toolRegistry;
    private AiProperties properties;
    private AiOperationsAgent operationsAgent;

    private AiClusterOperationsPort clusterPort;
    private AiWorkerOperationsPort workerPort;
    private AiTaskOperationsPort taskPort;
    private AiDlqOperationsPort dlqPort;
    private AiWorkflowOperationsPort workflowPort;
    private AiTenantOperationsPort tenantPort;

    @BeforeEach
    void setUp() {
        stubProvider = new StubAiProvider();
        properties = new AiProperties();
        properties.setActionTtlMinutes(30);

        clusterPort = mock(AiClusterOperationsPort.class);
        workerPort = mock(AiWorkerOperationsPort.class);
        taskPort = mock(AiTaskOperationsPort.class);
        dlqPort = mock(AiDlqOperationsPort.class);
        workflowPort = mock(AiWorkflowOperationsPort.class);
        tenantPort = mock(AiTenantOperationsPort.class);

        when(clusterPort.getClusterOverview()).thenReturn(
                new AiClusterOperationsPort.ClusterTelemetry("coord-01", 10L, true, 2, 0, 20)
        );
        when(workerPort.listWorkers()).thenReturn(List.of(
                new AiWorkerOperationsPort.WorkerSummary("worker-01", "ACTIVE", 10, 5, Instant.now())
        ));

        toolRegistry = new AiToolRegistry(clusterPort, workerPort, taskPort, dlqPort, workflowPort, tenantPort);
        operationsAgent = new AiOperationsAgent(stubProvider, toolRegistry, properties);
    }

    @Test
    @DisplayName("Analyzes cluster incident using verified evidence and bounded severity")
    void shouldAnalyzeClusterIncidentWithEvidence() {
        IncidentAnalysisRequest request = new IncidentAnalysisRequest(IncidentScope.CLUSTER, null, "tenant-alpha");

        IncidentAnalysisResponse response = operationsAgent.analyzeIncident(request, "tenant-alpha");

        assertNotNull(response);
        assertNotNull(response.analysisId());
        assertEquals(IncidentScope.CLUSTER, response.scope());
        assertNotNull(response.summary());
        assertEquals(AiIncidentSeverity.HIGH, response.severity());
        assertFalse(response.evidence().isEmpty(), "Evidence must be collected from real cluster port");
        assertFalse(response.likelyCauses().isEmpty());
        assertFalse(response.recommendations().isEmpty());

        var firstRec = response.recommendations().getFirst();
        assertNotNull(firstRec.actionId());
        assertEquals("tenant-alpha", firstRec.tenantId());
        assertEquals(AiActionType.RETRY_TASK, firstRec.actionType());
        assertEquals(AiActionRisk.LOW, firstRec.risk());
    }

    @Test
    @DisplayName("Gathers worker telemetry evidence during worker incident investigation")
    void shouldCollectWorkerEvidence() {
        when(workerPort.getWorker("worker-01")).thenReturn(Optional.of(
                new AiWorkerOperationsPort.WorkerDetail("worker-01", "SUSPECTED", 10, 8, Set.of("DATA_INGEST"), Instant.now(), Instant.now())
        ));

        IncidentAnalysisRequest request = new IncidentAnalysisRequest(IncidentScope.WORKER, "worker-01", "tenant-alpha");

        IncidentAnalysisResponse response = operationsAgent.analyzeIncident(request, "tenant-alpha");

        assertNotNull(response);
        verify(workerPort).getWorker("worker-01");
        assertFalse(response.evidence().isEmpty());
        assertEquals("worker", response.evidence().getFirst().source());
        assertEquals("worker-01", response.evidence().getFirst().identifier());
    }

    @Test
    @DisplayName("Gathers DLQ telemetry evidence during DLQ investigation")
    void shouldCollectDlqEvidence() {
        UUID taskId = UUID.randomUUID();
        when(dlqPort.inspectDlq(taskId)).thenReturn(Optional.of(
                new AiDlqOperationsPort.DlqDetail(taskId, "DATA_INGEST", "tenant-alpha", "Timeout after 3 retries", 3, Instant.now(), Instant.now(), List.of("Connection refused"))
        ));

        IncidentAnalysisRequest request = new IncidentAnalysisRequest(IncidentScope.DLQ, taskId.toString(), "tenant-alpha");

        IncidentAnalysisResponse response = operationsAgent.analyzeIncident(request, "tenant-alpha");

        assertNotNull(response);
        verify(dlqPort).inspectDlq(taskId);
        assertFalse(response.evidence().isEmpty());
        assertEquals("dlq", response.evidence().getFirst().source());
    }
}
