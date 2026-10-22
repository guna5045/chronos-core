# Chronos Core — System Walkthrough & Operational Lifecycle (Phase 13)

## 1. System Status
- **Current Phase**: Phase 13 (Observability, Testing & Chaos Engineering + Control Plane Hardening)
- **Runtime Stack**: Java 21, Spring Boot 3.4, PostgreSQL 16, Redis 7.2, gRPC 1.69, OpenTelemetry, Micrometer, React 18, Vite 6, TypeScript 5.

---

## 2. Canonical Task Lifecycle Walkthrough

```text
POST /api/v1/tasks (X-Request-Id: client-101)
     │
     ▼ [TraceAndCorrelationFilter] -> MDC (requestId, traceId)
[ CREATED ] (Validated against Tenant Quota & Idempotency Key)
     │
     ▼ [PostgreSQL INSERT via Outbox Pattern]
 [ QUEUED ] (Eligible for priority scheduler dispatch)
     │
     ▼ [Leader-Aware Scheduler with Redis Lock & Consistent Hashing]
[ DISPATCHED ] (Exclusive execution lease acquired in chronos_task_leases)
     │
     ▼ [gRPC Dispatch with W3C traceparent metadata]
 [ RUNNING ] (Worker executes task, streams heartbeats every 2s)
     │
     ├──► [ SUCCEEDED ] ──► (Terminal state; execution lease released)
     │
     ├──► [ CANCELLED ] ──► (Terminal state; cooperative cancellation gRPC)
     │
     └──► [ FAILED ]
             │
             ▼ (evaluate currentAttempt against maxAttempts)
      ┌──────────────┴──────────────┐
      │                             │
(currentAttempt < maxAttempts)     (currentAttempt >= maxAttempts)
      │                             │
      ▼                             ▼
  [ QUEUED ] (Exponential backoff) [ DLQ ] (Dead-Letter Queue; Terminal)
```

---

## 3. Workflow DAG Execution Walkthrough

```text
POST /api/v1/workflow-executions
     │
     ▼
Workflow Version Immutable Snapshot
     │
     ▼
[Root Nodes Activated: Parallel Fan-Out]
Node A1 (RUNNING)               Node A2 (RUNNING)
     │                                │
     ▼                                ▼
Node A1 (SUCCEEDED)             Node A2 (SUCCEEDED)
     │                                │
     └────────────────┬───────────────┘
                      │
                      ▼ [Barrier Fan-In Satisfaction]
                 Node B (RUNNING)
                      │
                      ▼
                 Node B (SUCCEEDED)
                      │
                      ▼
            Workflow Execution SUCCEEDED
```

---

## 4. Operational Control Plane Verification

1. **Overview Console (`/`)**: Real-time cluster status, active leader epoch, worker utilization, DLQ depth, and 5-state Recharts execution distribution.
2. **Tasks & DLQ (`/tasks`, `/dlq`)**: Interactive task submission, execution timeline, attempt logs, and authorized DLQ replay.
3. **Workers Fleet (`/workers`)**: Real-time heartbeat tracking, state transitions (`ACTIVE`, `SUSPECTED`, `REMOVED`), and capacity management.
4. **Observability (`/observability`)**: Direct link to `/actuator/prometheus`, health liveness/readiness probes, distributed tracing architecture, and verified chaos engineering scenarios.
5. **AI Assistants (`/ai/workflows`, `/ai/operations`)**: Controlled structured DAG authoring and operational triage with stale-action protection and human approval.
