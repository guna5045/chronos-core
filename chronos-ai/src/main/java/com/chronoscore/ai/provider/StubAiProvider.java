package com.chronoscore.ai.provider;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic in-memory AI provider for unit testing, offline development,
 * and predictable verification without external network calls.
 */
public class StubAiProvider implements AiProvider {

    private final String modelName;
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final AtomicBoolean simulateFailure = new AtomicBoolean(false);
    private final AtomicBoolean simulateTimeout = new AtomicBoolean(false);
    private final Queue<String> customResponses = new ConcurrentLinkedQueue<>();

    public StubAiProvider() {
        this("chronos-stub-v1");
    }

    public StubAiProvider(String modelName) {
        this.modelName = modelName;
    }

    @Override
    public String getProviderName() {
        return "stub";
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public boolean isAvailable() {
        return available.get();
    }

    public void setAvailable(boolean available) {
        this.available.set(available);
    }

    public void setSimulateFailure(boolean simulateFailure) {
        this.simulateFailure.set(simulateFailure);
    }

    public void setSimulateTimeout(boolean simulateTimeout) {
        this.simulateTimeout.set(simulateTimeout);
    }

    public void enqueueResponse(String jsonResponse) {
        this.customResponses.add(jsonResponse);
    }

    public void clearEnqueuedResponses() {
        this.customResponses.clear();
    }

    @Override
    public AiCompletionResponse generate(AiCompletionRequest request) {
        if (!available.get()) {
            throw new AiProviderException("stub", 503, "Stub AI Provider is currently marked unavailable");
        }
        if (simulateTimeout.get()) {
            throw new AiProviderException("stub", 504, "Stub AI Provider simulated timeout");
        }
        if (simulateFailure.get()) {
            throw new AiProviderException("stub", 500, "Stub AI Provider simulated internal error");
        }

        // 1. Check custom enqueued responses
        String custom = customResponses.poll();
        if (custom != null) {
            return new AiCompletionResponse(custom, modelName, 100, 200, Duration.ofMillis(10));
        }

        String prompt = request.userPrompt().toLowerCase();

        // 2. Error simulation keywords in prompt
        if (prompt.contains("sim_timeout")) {
            throw new AiProviderException("stub", 504, "Simulated AI timeout from prompt keyword");
        }
        if (prompt.contains("sim_failure")) {
            throw new AiProviderException("stub", 500, "Simulated AI failure from prompt keyword");
        }
        if (prompt.contains("sim_invalid_json")) {
            return new AiCompletionResponse("MALFORMED JSON {{{ [not valid", modelName, 50, 10, Duration.ofMillis(5));
        }
        if (prompt.contains("sim_cyclic")) {
            return new AiCompletionResponse(cyclicWorkflowJson(), modelName, 150, 250, Duration.ofMillis(15));
        }
        if (prompt.contains("sim_missing_nodes")) {
            return new AiCompletionResponse("{\"workflowName\":\"test\",\"nodes\":[]}", modelName, 50, 50, Duration.ofMillis(5));
        }

        // 3. Operational incident analysis simulation
        if (request.systemPrompt().contains("AI Operations Agent") || prompt.contains("incident") || prompt.contains("evidence")) {
            return new AiCompletionResponse(defaultIncidentAnalysisJson(prompt), modelName, 200, 300, Duration.ofMillis(20));
        }

        // 4. Default workflow proposal simulation
        return new AiCompletionResponse(defaultWorkflowProposalJson(request.userPrompt()), modelName, 150, 250, Duration.ofMillis(15));
    }

    private String defaultWorkflowProposalJson(String userPrompt) {
        return """
        {
          "workflowName": "data-pipeline-generated",
          "description": "Generated DAG for: %s",
          "nodes": [
            {
              "nodeId": "extract-data",
              "taskType": "DATA_EXTRACT",
              "dependsOn": [],
              "timeoutSeconds": 300,
              "maxRetries": 3,
              "payload": "{}"
            },
            {
              "nodeId": "transform-data",
              "taskType": "DATA_TRANSFORM",
              "dependsOn": ["extract-data"],
              "timeoutSeconds": 600,
              "maxRetries": 2,
              "payload": "{}"
            },
            {
              "nodeId": "load-data",
              "taskType": "DATA_LOAD",
              "dependsOn": ["transform-data"],
              "timeoutSeconds": 300,
              "maxRetries": 3,
              "payload": "{}"
            }
          ],
          "explanation": "Sequential DAG: extract-data executes first, followed by transform-data, and completes with load-data."
        }
        """.formatted(escapeJson(userPrompt));
    }

    private String cyclicWorkflowJson() {
        return """
        {
          "workflowName": "cyclic-pipeline-test",
          "description": "Intentional cycle test",
          "nodes": [
            {
              "nodeId": "node-a",
              "taskType": "TASK_A",
              "dependsOn": ["node-c"],
              "timeoutSeconds": 60,
              "maxRetries": 1
            },
            {
              "nodeId": "node-b",
              "taskType": "TASK_B",
              "dependsOn": ["node-a"],
              "timeoutSeconds": 60,
              "maxRetries": 1
            },
            {
              "nodeId": "node-c",
              "taskType": "TASK_C",
              "dependsOn": ["node-b"],
              "timeoutSeconds": 60,
              "maxRetries": 1
            }
          ],
          "explanation": "Cyclic workflow: A -> B -> C -> A."
        }
        """;
    }

    private String defaultIncidentAnalysisJson(String prompt) {
        return """
        {
          "summary": "Operational analysis completed. Investigated target telemetry and task lifecycle transitions.",
          "severity": "HIGH",
          "likelyCauses": [
            "Worker resource saturation or transient network lease expiration",
            "Downstream service dependency timeout causing cascading retry exhaustion"
          ],
          "recommendations": [
            {
              "title": "Retry failed task with transient error",
              "reason": "Execution attempt failed due to transient connection timeout; worker health is currently stable.",
              "risk": "LOW",
              "action": "RETRY_TASK",
              "targetId": "00000000-0000-0000-0000-000000000001"
            },
            {
              "title": "Replay dead-letter queue task",
              "reason": "Root cause identified as temporary database outage which has now recovered.",
              "risk": "MEDIUM",
              "action": "REPLAY_DLQ",
              "targetId": "00000000-0000-0000-0000-000000000002"
            }
          ]
        }
        """;
    }

    private String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
