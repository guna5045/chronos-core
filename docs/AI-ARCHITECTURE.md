# Chronos Core &mdash; AI Workflow & Operations Architecture

## 1. Executive Summary & Core Architectural Principle

Phase 12 of Chronos Core introduces two specialized, deterministic-boundary AI capabilities:
1. **AI Workflow Agent**: Synthesizes natural language requirements into structured, schema-validated Directed Acyclic Graph (DAG) workflow proposals.
2. **AI Operations Agent**: Correlates live operational telemetry across workers, tasks, dead-letter queues, and clusters to diagnose incidents and formulate controlled operational recommendations.

### The Authoritative Core Principle
> **The LLM must NEVER become the source of truth for distributed-system correctness.**

The deterministic Chronos Core engine remains strictly authoritative:
- **Distributed Coordination**: Lease-based leader election with TTL expiration, monotonic fencing tokens, and distributed locks remain deterministic backend mechanisms outside the reach of the LLM.
- **Workflow Correctness**: The AI agent may propose DAGs, but the deterministic `WorkflowDagValidator` (Kahn's algorithm) deterministically verifies acyclicity and valid dependency flow. The model is never trusted to determine if a graph is acyclic.
- **Mutating Interventions**: All mutating operations (task retry/cancel, DLQ replay/archive, workflow cancellation) require explicit human operator approval via an RBAC and tenant-validated approval gate protected by stale-state verification.
- **No Unrestricted Execution**: The AI module has zero access to PostgreSQL, Redis, shell commands, arbitrary HTTP endpoints, or unconstrained reflection/code execution.

```text
                    CHRONOS CONTROL PLANE (React / TS)
                                    │
                                    ▼
                         REST /api/v1/ai/*
                                    │
                ┌───────────────────┴───────────────────┐
                ▼                                       ▼
       AI Workflow Agent                       AI Operations Agent
  (NL -> Structured Proposal)              (Telemetry Correlation & Triage)
                │                                       │
                ▼                                       ▼
   Structured Workflow Proposal              Operational Findings & Actions
                │                                       │
                └───────────────────┬───────────────────┘
                                    ▼
                          Policy / Validation
                   (Kahn DAG, Schema, Prompt Def.)
                                    │
                                    ▼
                          Human Approval Gate
                   (RBAC, Tenant Check, Stale Check)
                                    │
                                    ▼
                      Existing Application Services
                (WorkflowService, TaskService, DlqService)
                                    │
                                    ▼
                             Deterministic Core
```

---

## 2. AI Provider Abstraction

Chronos Core decouples the intelligence layer from specific LLM providers via a clean provider interface:

```java
public interface AiProvider {
    AiCompletionResponse complete(AiCompletionRequest request);
    boolean isAvailable();
    String getProviderName();
    String getModelName();
}
```

### Implementations:
1. **`OpenAiCompatibleProvider`**:
   - Integrates with any OpenAI-compatible API endpoint (OpenAI, Azure OpenAI, Ollama, vLLM, LocalAI) via Java 21 `HttpClient` and Spring `RestClient`.
   - Supports system instructions, temperature bounding, and JSON-schema output enforcement (`response_format: { type: "json_object" }`).
   - Server-side timeouts (default 30s) and bounded request retry policies.
2. **`StubAiProvider`**:
   - A fully deterministic, offline stub provider designed for automated unit tests, CI/CD pipelines, and air-gapped environments without external internet connectivity.
   - Provides pre-validated workflow DAG responses, telemetry diagnoses, and predictable failure-mode simulations (timeout, malformed JSON, schema violations).

### Configuration (`application.yml` / Environment Variables):
```properties
CHRONOS_AI_ENABLED=true
CHRONOS_AI_PROVIDER=OPENAI_COMPATIBLE # or STUB
CHRONOS_AI_BASE_URL=https://api.openai.com/v1
CHRONOS_AI_API_KEY=${AI_API_KEY:}
CHRONOS_AI_MODEL=gpt-4o-mini
CHRONOS_AI_TIMEOUT_SECONDS=30
CHRONOS_AI_ACTION_TTL_MINUTES=30
CHRONOS_AI_MAX_PROMPT_LENGTH=4000
```

> **Security Note**: `CHRONOS_AI_API_KEY` remains strictly server-side. The control plane API never exposes secrets or raw credentials (`/api/v1/ai/status` returns only provider name, model, and availability).

---

## 3. Strict Failure Isolation (Optional AI)

If the AI provider times out, returns malformed JSON, exceeds rate limits, or is completely disabled (`CHRONOS_AI_ENABLED=false`):
- **Core Orchestration Continues Unaffected**: Task scheduling, worker heartbeats, leader election, lease renewal, DLQ routing, retry recovery, and workflow execution continue operating normally without interruption.
- **Circuit Protection**: AI failures bubble up as safe REST error responses (HTTP 502 `AI_PROVIDER_ERROR` or HTTP 503 `AI_DISABLED`) without impacting application transactions or coordinator threads.

---

## 4. Controlled AI Tool Registry & Port Architecture

The AI module interacts with Chronos operational data solely through explicit, strongly typed ports and read-only tools:

### Ports (`com.chronoscore.ai.ports`):
- `AiTaskOperationsPort`: Read task state, submit retry, cancel task.
- `AiWorkerOperationsPort`: Read worker cluster state, inspect worker metrics.
- `AiWorkflowOperationsPort`: Inspect definitions, inspect DAG execution telemetry, materialize approved drafts.
- `AiDlqOperationsPort`: List dead-letter entries, inspect poison pills, replay/archive.
- `AiClusterOperationsPort`: Retrieve cluster coordination state, leader node status, and partition metrics.
- `AiTenantOperationsPort`: Retrieve tenant quotas and concurrency limits.

### Tool Registry (`AiToolRegistry`):
Every tool registered has explicit permission levels and read-only flags:

| Tool Identifier | Type | Permission | Description |
|---|---|---|---|
| `cluster.status` | Read-Only | VIEWER | Retrieves leader node, lease TTL, and worker count |
| `workers.list` | Read-Only | VIEWER | Lists active, suspect, and removed worker nodes |
| `worker.inspect` | Read-Only | VIEWER | Inspects worker capacity, active tasks, and heartbeats |
| `tasks.search` | Read-Only | VIEWER | Queries tasks by tenant, state, and type (capped) |
| `task.inspect` | Read-Only | VIEWER | Inspects task attempts, failure reason, and payload |
| `dlq.list` | Read-Only | VIEWER | Lists DLQ entries with error classifications |
| `dlq.inspect` | Read-Only | VIEWER | Inspects poison pill payload and root failure cause |
| `workflow.inspect` | Read-Only | VIEWER | Retrieves workflow DAG topology and active versions |
| `workflow.execution.inspect` | Read-Only | VIEWER | Retrieves node-level execution telemetry |
| `task.retry` | Recommendation | OPERATOR | Recommends task retry (requires approval) |
| `task.cancel` | Recommendation | OPERATOR | Recommends task cancellation (requires approval) |
| `dlq.replay` | Recommendation | OPERATOR | Recommends DLQ message replay (requires approval) |
| `dlq.archive` | Recommendation | OPERATOR | Recommends DLQ entry archiving (requires approval) |
| `workflow.cancel` | Recommendation | OPERATOR | Recommends workflow abort (requires approval) |
| `worker.drain` | Recommendation | OPERATOR | Recommends draining worker node (requires approval) |

> **Hard Isolation**: No tool executes raw SQL (`SELECT ...`), Redis commands (`SET ...`), shell processes (`Runtime.getRuntime().exec()`), or arbitrary HTTP requests.

---

## 5. AI Workflow Agent: Pipeline & Verification

### Pipeline Flow:
```text
User Natural Language Prompt
         │
         ▼
Prompt Injection Sanitation (Max 4,000 chars, isolated delimiters)
         │
         ▼
AiProvider (JSON Schema Enforced)
         │
         ▼
JSON Parsing & DTO Deserialization
         │
         ▼
Deterministic WorkflowDagValidator (Kahn's Topological Sort & Cycle Detection)
         │
         ▼
Persisted Proposal (Status: PENDING_REVIEW, 30m TTL)
         │
         ▼
Operator Reviews DAG in Control Plane
         │
         ├───────────────────────────────┐
         ▼                               ▼
      Reject                          Approve (OPERATOR/ADMIN)
  (Status: REJECTED)                     │
                                         ▼
                             Tenant Context Assertion
                                         │
                                         ▼
                            WorkflowService.createWorkflow()
                            WorkflowService.createDraftVersion()
```

### Deterministic DAG Verification:
The LLM is prompted to structure the DAG, but its output is fed directly into `WorkflowDagValidator.validate(nodes, edges)`:
- Self-referencing cycles (`A -> A`) are caught and flagged as `INVALID`.
- Multi-node cycles (`A -> B -> C -> A`) are deterministically detected via in-degree tracking.
- Disconnected or dangling edge references are rejected.
- Acyclic graphs are marked `valid = true` and permitted for human review.

### Workflow Immutability:
Approving an AI proposal never mutates an existing published version. It creates a new registered workflow namespace and a Draft Version 1, respecting Phase 08 workflow versioning semantics.

---

## 6. AI Operations Agent: Telemetry & Human-In-The-Loop Approval

### Evidence-Based Diagnostics:
The Operations Agent does not hallucinate state. Every diagnosis is accompanied by verified telemetry evidence records:
- **Evidence Record**: `{ source: "worker", identifier: "worker-02", summary: "Worker missed 3 heartbeats", status: "SUSPECTED" }`
- **Severity Classification**: Deterministic, bounded enum: `INFO`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`.
- **Likely Causes**: Correlated failure patterns (e.g. database deadlocks, downstream timeout propagation).

### Human-In-The-Loop Action Approval Gate:
All mutating recommendations are registered as `AiOperationalAction` records with:
- `actionId`: Unique UUID.
- `actionType`: Strongly typed enum (`RETRY_TASK`, `CANCEL_TASK`, `REPLAY_DLQ`, `ARCHIVE_DLQ`, `CANCEL_WORKFLOW`, `DRAIN_WORKER`).
- `risk`: Bounded enum (`LOW`, `MEDIUM`, `HIGH`).
- `expiresAt`: Instant (default 30 minutes TTL).
- `status`: `PENDING_APPROVAL`.

### Stale State Protection (Mandatory Guarantee):
Before executing an approved action, the `AiActionApprovalService` re-fetches the target entity from Chronos persistence:
1. **Target Existence & Tenant Check**: Verifies target belongs to authenticated tenant.
2. **State Transition Check**: If the target's live state has changed since the recommendation was made (e.g. a failed task has already been retried and succeeded, or is now `RUNNING`), the approval is rejected immediately with HTTP 409 Conflict (`STALE_ACTION_STATE`).
3. **Expiration Check**: If the action has exceeded its TTL, execution is rejected with HTTP 410 Gone (`ACTION_EXPIRED`).
4. **Audit Trail**: Every approval, rejection, and execution outcome is logged to the in-memory audit buffer and structured system logs.

---

## 7. Security & Prompt Injection Defense

1. **Role-Based Access Control (RBAC)**:
   - `VIEWER`: Can view status, inspect proposals, inspect telemetry, and trigger incident analysis. Cannot generate proposals or approve mutating actions.
   - `OPERATOR`: Can generate workflow proposals, review proposals, approve valid proposals, and approve operational actions within their tenant.
   - `ADMIN`: Full operational access including cross-tenant administration.
2. **Strict Multi-Tenant Isolation**:
   - `tenantId` is always extracted from the authenticated JWT `SecurityContext`.
   - Tool operations enforce tenant filters server-side. The LLM cannot access cross-tenant data even if instructed in natural language prompts.
3. **Prompt Injection Defense**:
   - System instructions, tool schemas, and operational data are isolated from untrusted user inputs.
   - User workflow prompts are encapsulated in `<user_workflow_description>` boundaries with strict input-length bounding (4,000 characters max).
   - Untrusted task payloads, error messages, and log records are treated strictly as data literals and sanitized before ingestion into prompt templates.

---

## 8. REST API Reference

| Method | Endpoint | Role | Description |
|---|---|---|---|
| `GET` | `/api/v1/ai/status` | Public / Any | Returns AI engine enablement, provider, and model |
| `POST` | `/api/v1/ai/workflows/generate` | `OPERATOR`, `ADMIN` | Synthesizes a structured DAG proposal from natural language |
| `GET` | `/api/v1/ai/workflows` | `VIEWER`+ | Lists historical workflow proposals for tenant |
| `GET` | `/api/v1/ai/workflows/{id}` | `VIEWER`+ | Retrieves detailed proposal specification and validation status |
| `POST` | `/api/v1/ai/workflows/{id}/approve` | `OPERATOR`, `ADMIN` | Approves proposal and materializes draft workflow |
| `POST` | `/api/v1/ai/workflows/{id}/reject` | `OPERATOR`, `ADMIN` | Marks proposal as rejected |
| `POST` | `/api/v1/ai/incidents/analyze` | `VIEWER`+ | Correlates telemetry evidence into an incident diagnosis |
| `GET` | `/api/v1/ai/actions` | `VIEWER`+ | Lists operational recommendation registry |
| `GET` | `/api/v1/ai/actions/{id}` | `VIEWER`+ | Retrieves specific operational recommendation |
| `POST` | `/api/v1/ai/actions/{id}/approve` | `OPERATOR`, `ADMIN` | Approves action with stale-state check and executes mutation |
| `POST` | `/api/v1/ai/actions/{id}/reject` | `OPERATOR`, `ADMIN` | Rejects and discards recommendation |

---

## 9. Limitations & Phase Boundaries

- **No Prometheus / Grafana / OpenTelemetry**: Metric instrumentation belongs to Phase 13.
- **No Kubernetes / Helm**: Cloud deployment manifests belong to Phase 14.
- **No Chaos Engineering**: Fault-injection test suites belong to Phase 14/15.
- **No Autonomous Destruction**: The system will never automatically execute destructive operational actions without human review.
