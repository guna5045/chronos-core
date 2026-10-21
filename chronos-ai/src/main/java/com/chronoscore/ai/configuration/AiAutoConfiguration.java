package com.chronoscore.ai.configuration;

import com.chronoscore.ai.agent.operations.AiOperationsAgent;
import com.chronoscore.ai.agent.workflow.AiWorkflowAgent;
import com.chronoscore.ai.audit.AiAuditService;
import com.chronoscore.ai.policy.AiActionApprovalService;
import com.chronoscore.ai.ports.AiClusterOperationsPort;
import com.chronoscore.ai.ports.AiDlqOperationsPort;
import com.chronoscore.ai.ports.AiTaskOperationsPort;
import com.chronoscore.ai.ports.AiTenantOperationsPort;
import com.chronoscore.ai.ports.AiWorkerOperationsPort;
import com.chronoscore.ai.ports.AiWorkflowOperationsPort;
import com.chronoscore.ai.provider.AiProvider;
import com.chronoscore.ai.provider.OpenAiCompatibleProvider;
import com.chronoscore.ai.provider.StubAiProvider;
import com.chronoscore.ai.service.AiOperationsService;
import com.chronoscore.ai.service.AiStatusService;
import com.chronoscore.ai.service.AiWorkflowService;
import com.chronoscore.ai.tools.AiToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Auto-Configuration assembling the Chronos Core AI layer.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AiProvider aiProvider(AiProperties properties) {
        String providerType = properties.getProvider();
        if ("openai-compatible".equalsIgnoreCase(providerType)) {
            log.info("Configuring OpenAI-compatible AI Provider with baseUrl='{}', model='{}'",
                    properties.getBaseUrl(), properties.getModel());
            return new OpenAiCompatibleProvider(
                    properties.getBaseUrl(),
                    properties.getApiKey(),
                    properties.getModel(),
                    Duration.ofSeconds(properties.getTimeoutSeconds())
            );
        } else {
            log.info("Configuring Deterministic Stub AI Provider (model='{}')", properties.getModel());
            return new StubAiProvider(properties.getModel());
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public AiAuditService aiAuditService() {
        return new AiAuditService();
    }

    // Default No-Op Ports for standalone testing in chronos-ai module
    @Bean
    @ConditionalOnMissingBean
    public AiClusterOperationsPort defaultClusterPort() {
        return () -> new AiClusterOperationsPort.ClusterTelemetry("coordinator-leader", 1L, true, 0, 0, 0);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiWorkerOperationsPort defaultWorkerPort() {
        return new AiWorkerOperationsPort() {
            @Override
            public List<WorkerSummary> listWorkers() {
                return List.of();
            }

            @Override
            public Optional<WorkerDetail> getWorker(String workerId) {
                return Optional.empty();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public AiTaskOperationsPort defaultTaskPort() {
        return new AiTaskOperationsPort() {
            @Override
            public Optional<com.chronoscore.common.dto.TaskSummaryDTO> getTask(UUID taskId) {
                return Optional.empty();
            }

            @Override
            public List<com.chronoscore.common.dto.TaskSummaryDTO> searchTasks(String tenantId, String status, String taskType, int limit) {
                return List.of();
            }

            @Override
            public void retryTask(UUID taskId, String principal) {}

            @Override
            public void cancelTask(UUID taskId, String principal, String reason) {}
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public AiDlqOperationsPort defaultDlqPort() {
        return new AiDlqOperationsPort() {
            @Override
            public List<DlqSummary> listDlq(String tenantId, int limit) {
                return List.of();
            }

            @Override
            public Optional<DlqDetail> inspectDlq(UUID taskId) {
                return Optional.empty();
            }

            @Override
            public void replayDlq(UUID taskId, String principal) {}

            @Override
            public void archiveDlq(UUID taskId, String principal, String reason) {}
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public AiWorkflowOperationsPort defaultWorkflowPort() {
        return new AiWorkflowOperationsPort() {
            @Override
            public WorkflowCreatedResult createWorkflow(String name, String description,
                                                        List<com.chronoscore.common.domain.workflow.WorkflowNodeDefinition> nodes,
                                                        List<com.chronoscore.common.domain.workflow.WorkflowEdge> edges) {
                return new WorkflowCreatedResult(UUID.randomUUID(), name, 1, "DRAFT");
            }

            @Override
            public Optional<WorkflowExecutionDetail> getExecution(UUID executionId) {
                return Optional.empty();
            }

            @Override
            public void cancelExecution(UUID executionId, String principal) {}
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public AiTenantOperationsPort defaultTenantPort() {
        return tenantId -> Optional.empty();
    }

    @Bean
    @ConditionalOnMissingBean
    public AiToolRegistry aiToolRegistry(AiClusterOperationsPort clusterPort,
                                         AiWorkerOperationsPort workerPort,
                                         AiTaskOperationsPort taskPort,
                                         AiDlqOperationsPort dlqPort,
                                         AiWorkflowOperationsPort workflowPort,
                                         AiTenantOperationsPort tenantPort) {
        return new AiToolRegistry(clusterPort, workerPort, taskPort, dlqPort, workflowPort, tenantPort);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiWorkflowAgent aiWorkflowAgent(AiProvider aiProvider, AiProperties properties) {
        return new AiWorkflowAgent(aiProvider, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiOperationsAgent aiOperationsAgent(AiProvider aiProvider, AiToolRegistry toolRegistry, AiProperties properties) {
        return new AiOperationsAgent(aiProvider, toolRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiActionApprovalService aiActionApprovalService(AiTaskOperationsPort taskPort,
                                                           AiDlqOperationsPort dlqPort,
                                                           AiWorkflowOperationsPort workflowPort,
                                                           AiAuditService auditService) {
        return new AiActionApprovalService(taskPort, dlqPort, workflowPort, auditService);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiWorkflowService aiWorkflowService(AiWorkflowAgent workflowAgent,
                                               AiActionApprovalService approvalService,
                                               AiProperties properties) {
        return new AiWorkflowService(workflowAgent, approvalService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiOperationsService aiOperationsService(AiOperationsAgent operationsAgent,
                                                 AiActionApprovalService approvalService,
                                                 AiProperties properties) {
        return new AiOperationsService(operationsAgent, approvalService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiStatusService aiStatusService(AiProperties properties, AiProvider aiProvider) {
        return new AiStatusService(properties, aiProvider);
    }
}
