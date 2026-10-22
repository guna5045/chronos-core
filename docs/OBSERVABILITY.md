# Chronos Core — Observability & Distributed Tracing Architecture

## 1. Overview & Architecture Boundary

Observability in Chronos Core is designed around the **Three Pillars of Observability**:
1. **Metrics** (`IMPLEMENTED` / `VERIFIED`): Micrometer instrumentation exported via Prometheus Actuator (`/actuator/prometheus`).
2. **Distributed Tracing & Correlation** (`IMPLEMENTED` / `VERIFIED` in-process; `PARTIAL / OPTIONAL` external collector): OpenTelemetry bridge with W3C Trace Context and MDC correlation across REST, Scheduler, and gRPC metadata headers.
3. **Structured Logging** (`IMPLEMENTED` / `VERIFIED`): Consistent operational logs enriched with `requestId`, `traceId`, `spanId`, `tenantId`, and `errorCode`.

```text
Chronos Core (Coordinator / Worker)
     │
     ├─────────────── Micrometer Metrics ───────────► Prometheus Actuator (/actuator/prometheus) [VERIFIED]
     │                                                         │
     │                                                         ▼
     │                                                      Grafana (Dashboards 1-6) [CONFIGURED]
     │
     ├─────────────── W3C Trace Context ────────────► OpenTelemetry Bridge [PARTIAL / OPTIONAL]
     │                (REST -> Scheduler -> gRPC)              │ (Correlation headers verified;
     │                                                         ▼  external collector optional)
     │                                                    Trace Collector / Jaeger
     │
     └─────────────── Structured MDC Logs ──────────► Standard Out / Log Aggregator [VERIFIED]
                      (requestId, traceId, tenantId)
```

### Capability Verification Status
- **Low-Cardinality Metrics**: `IMPLEMENTED` & `VERIFIED` (Micrometer + Prometheus Actuator).
- **Liveness & Readiness Probes**: `IMPLEMENTED` & `VERIFIED` (`/actuator/health/liveness`, `/actuator/health/readiness`).
- **Trace Context & Correlation**: `IMPLEMENTED` & `VERIFIED` (MDC injection, `X-Request-Id`, `X-Trace-Id`, gRPC metadata).
- **External OpenTelemetry Collector**: `OPTIONAL` (Collector not bundled in local environment).
- **Grafana Provisioning**: `IMPLEMENTED` (6 dashboard JSON models in `infrastructure/grafana/dashboards/`).

---

## 2. Low-Cardinality Metric Catalog

To prevent Prometheus memory exhaustion and time-series explosion, **no arbitrary identifiers** (such as task IDs, user IDs, request IDs, prompts, or payloads) are ever used as metric tags.

### A. Task Lifecycle Metrics
- `chronos.tasks.submitted` (Counter): Total tasks submitted and accepted via admission control.
  - Tags: `tenant` (bounded), `priority` (`LOW`, `NORMAL`, `HIGH`, `CRITICAL`)
- `chronos.tasks.dispatched` (Counter): Tasks successfully dispatched across gRPC channel to execution workers.
- `chronos.tasks.completed` (Counter): Tasks reaching a terminal state.
  - Tags: `status` (`SUCCEEDED`, `FAILED`)
- `chronos.tasks.dlq` (Counter): Tasks exhausting their retry policy routed to Dead-Letter Queue.
- `chronos.tasks.inflight` (Gauge): Current tasks in active `RUNNING` state.
- `chronos.tasks.queue.depth` (Gauge): Current tasks waiting in `QUEUED` state.
- `chronos.task.queue.wait` (Timer): Latency distribution between task submission and scheduler dispatch.
- `chronos.task.dispatch` (Timer): Duration to evaluate worker selection, acquire row lock, and dispatch gRPC request.
- `chronos.task.execution` (Timer): End-to-end task execution duration on the worker node.

### B. Scheduler & Coordination Metrics
- `chronos.scheduler.polls` (Counter): Dispatch cycles executed by the leader scheduler.
- `chronos.scheduler.rejections` (Counter): Tasks rejected during admission control due to tenant concurrency/rate limits.
  - Tags: `reason` (`RATE_LIMIT`, `CONCURRENCY_LIMIT`, `SYSTEM_BACKPRESSURE`)
- `chronos.leader.epoch` (Gauge): Active fencing token epoch held by lease-based Redis leader election.
- `chronos.leader.acquired` (Counter): Total times the current coordinator acquired leader lease.
- `chronos.leader.lost` (Counter): Total leadership lease loss or expiration events.
- `chronos.coordination.lock.attempts` (Counter): Distributed lock acquisition requests in Redis.
- `chronos.coordination.lock.failed` (Counter): Contention failures when attempting to acquire distributed locks.

### C. Worker Fleet Metrics
- `chronos.workers.active` (Gauge): Heartbeat-verified live worker execution nodes.
- `chronos.workers.suspected` (Gauge): Workers that have missed their heartbeat threshold.
- `chronos.workers.capacity.available` (Gauge): Total available execution concurrency slots across the active fleet.
- `chronos.workers.evicted` (Counter): Evicted worker count due to heartbeat timeout expiration.
- `chronos.workers.heartbeats` (Counter): Heartbeat streams processed by coordinator.

### D. Workflow DAG Metrics
- `chronos.workflows.active` (Gauge): Concurrently running workflow DAG executions.
- `chronos.workflows.completed` (Counter): Terminal workflow completions.
  - Tags: `status` (`SUCCEEDED`, `FAILED`, `CANCELLED`)
- `chronos.workflows.node.activations` (Counter): DAG node transitions from pending to active.
- `chronos.workflow.execution.duration` (Timer): Total time from workflow launch to all terminal dependencies.

### E. AI Subsystem Metrics
- `chronos.ai.requests` (Counter): Total invocations to the AI Workflow / Operations agents.
- `chronos.ai.failures` (Counter): Invocations resulting in provider timeout or schema invalidation.
- `chronos.ai.proposals` (Counter): Workflow DAG draft definitions generated.
- `chronos.ai.actions.approved` (Counter): Operational triage recommendations approved by human operators.
- `chronos.ai.actions.stale` (Counter): Actions rejected during execution due to stale pre-condition check.
- `chronos.ai.provider.latency` (Timer): Latency distribution of upstream LLM provider calls.

---

## 3. Distributed Tracing Flow

Chronos Core propagates W3C Trace Context (`traceparent` header) across asynchronous boundaries:

```text
POST /tasks (Inbound HTTP)
   │
   ▼ [TraceAndCorrelationFilter] -> Generates/Propagates X-Request-Id, X-Trace-Id into MDC
Task Service (task.submit span)
   │
   ▼ [PostgreSQL INSERT] (Database commit, audit record generated)
Leader Scheduler (task.schedule span)
   │
   ▼ [Redis lock acquisition + Consistent Hash selection]
gRPC Worker Dispatch (grpc.dispatch span, W3C traceparent injected into Metadata)
   │
   ▼ [Worker Heartbeat stream]
Worker Execution Runner (worker.execute span)
   │
   ▼ [TaskResult gRPC Report]
Task Completion & Outbox (task.complete span, fencing epoch verified)
```

---

## 4. Health Probes & Actuator Security

Chronos Core exposes standardized Spring Boot Actuator health probes:
- `/actuator/health/liveness`: Checks whether the JVM process is alive and internal event loops are responsive. Temporary loss of PostgreSQL or Redis does NOT trigger a liveness failure to prevent unnecessary Kubernetes/process restart loops.
- `/actuator/health/readiness`: Checks whether the instance can safely accept new HTTP traffic and coordinate with Redis and PostgreSQL.
- `/actuator/prometheus`: Exposes all registered Micrometer gauges, counters, and percentiles for scraping.

---

## 5. Structured Error Taxonomy

All REST endpoints return uniform `ApiErrorResponse` structures without internal stack traces, DB credentials, or SQL leaks:

```json
{
  "timestamp": "2026-10-22T04:30:00Z",
  "status": 503,
  "code": "COORDINATOR_UNREACHABLE",
  "message": "Chronos Core coordinator process is unreachable (connection refused on http://localhost:8080).",
  "path": "/api/v1/cluster/overview",
  "requestId": "client-1729571400000-abc1234",
  "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
}
```
