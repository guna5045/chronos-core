# Chronos Core — AI Agents Safety, Invariants & Architecture

## 1. Role of AI in Chronos Core

In strict adherence to the project philosophy:
- **Distributed Systems First**: Concurrency, state machines, lease-based elections, and transactional outbox remain authoritative.
- **Strictly Scoped AI**: Chronos Core does **not** introduce generic conversational chatbots or extraneous LLM integrations.
- Only two focused AI capabilities exist:
  1. **AI Workflow Agent**: Assisting engineers in authoring and validating complex workflow DAG definitions.
  2. **AI Operations Agent**: Assisting operators with log root-cause analysis and operational triage recommendations.

---

## 2. AI Workflow Agent Lifecycle

```text
Natural Language Requirement
   │
   ▼
LLM Provider (Structured JSON Generation)
   │
   ▼
Schema Validation (JSON Schema conforming to WorkflowDefinition)
   │
   ▼
DAG Validation (Kahn's algorithm: cycle detection, dangling edge check)
   │
   ▼
Tenant Policy Validation (Node counts, timeout limits, allowed task types)
   │
   ▼
Draft Proposal Created (Status: PENDING_REVIEW, 30-Minute TTL)
   │
   ▼
Human Engineering Approval (Operator clicks "Approve Proposal" via Control Plane)
   │
   ▼
Immutable Workflow Version Published & Persisted in PostgreSQL
```

### Safety Guarantees:
- **No Direct Execution**: The LLM output is purely a draft data model; it never directly executes shell commands, SQL queries, or network requests.
- **Prompt Injection Defense**: All user requirements are encapsulated within strict structured system prompts with strict JSON Schema output enforcement.

---

## 3. AI Operations Agent & Controlled Triage

The AI Operations Agent inspects read-only telemetry and generates recommended triage actions:

```text
Cluster Incident Detected (Missed heartbeats, high DLQ, rate limiting)
   │
   ▼
Read-Only Telemetry Collection (Workers, Tasks, DLQ, Metrics)
   │
   ▼
Operational Analysis Generated:
  - Finding: Root cause diagnosis
  - Evidence: Specific worker IDs, error codes, and timestamps
  - Severity: LOW, MEDIUM, HIGH, CRITICAL
  - Recommendation: Action proposal (e.g., DRAIN_WORKER, REPLAY_DLQ)
   │
   ▼
Action Proposed (Status: PENDING_APPROVAL, Action TTL: 30 minutes)
   │
   ▼
Human Operator Review & Confirmation
   │
   ▼
State Revalidation Pre-Check:
  Does current system state match the recommendation's baseline evidence?
  - IF STATE CHANGED: Action rejected as STALE (e.g., worker recovered)
  - IF STATE UNCHANGED: Validated action dispatched through Core API
   │
   ▼
Audit Record Emitted in PostgreSQL (tenantId, actor, evidence, actionId, requestId)
```

---

## 4. Stale Action Protection & TTL Invariant

Every proposed operational action is assigned:
- A unique `actionId`.
- An `evidence` snapshot capturing the exact worker state, task version, or DLQ status at the moment of analysis.
- An `expiresAt` timestamp set to 30 minutes from generation.

Before any approved action is executed by `AiController`:
1. `expiresAt` is checked: if `Instant.now().isAfter(expiresAt)`, the action is transitioned to `EXPIRED` and rejected.
2. The current target entity (e.g. Worker node) is re-queried from the registry: if its state has changed (for instance, the worker resumed sending heartbeats and returned to `ACTIVE`), the action is rejected with `STALE_ACTION_PRECONDITION_FAILED`.

---

## 5. Circuit-Breaker & Decoupled Execution Invariant

> **INVARIANT 7**: AI failures cannot stop core task execution or cluster scheduling.

If the upstream LLM provider:
- Times out (> 10s),
- Returns HTTP 500 or 429,
- Returns invalid or unparseable JSON,
- Or is completely unreachable,

The Chronos Coordinator logs the incident, surfaces a graceful fallback response to the Control Plane (`AI_PROVIDER_UNAVAILABLE`), and **continues normal task scheduling, worker heartbeats, and workflow execution without interruption**.
