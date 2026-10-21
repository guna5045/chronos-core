# Chronos Core - Architectural Specification

## Status
- **Current Phase**: Phase 02 (Core Domain Model & Shared Contracts)
- **Document Version**: 0.2.0

---

## 1. High-Level System Architecture

Chronos Core is architected as a distributed, partitioned, master-worker platform optimized for durability and high throughput.

```
                    ┌─────────────────────────┐
                    │ Engineering Control     │
                    │ Plane (Dashboard)       │
                    └────────────┬────────────┘
                                 │ HTTP / REST
                    ┌────────────▼────────────┐
                    │   Chronos Coordinator   │◄──────┐
                    │   (Active Leader)       │       │
                    └──────┬──────┬─────┬─────┘       │ Redis Lease Election
                           │      │     │             │
        Task Dispatch / gRPC      │     └─────────────┼───────────────┐
                           │      │                   │               │
        ┌──────────────────┘      │ PostgreSQL        ▼               ▼
        │                         │ (Durable DB)  ┌────────┐     ┌────────┐
        ▼                         │               │ Redis  │     │ Standby│
┌──────────────┐                  ▼               │ Lease  │     │ Coord. │
│Chronos Worker│           ┌──────────────┐       │ Store  │     └────────┘
│   Node 1     │           │ Task & DAG   │       └────────┘
└──────────────┘           │ Audit Records│
        ▲                  └──────────────┘
        │
┌──────────────┐
│Chronos Worker│
│   Node 2     │
└──────────────┘
```

---

## 2. Core Domain Contracts (`chronos-common`)

Phase 02 establishes the deterministic core domain contracts decoupled from infrastructure and persistence runtimes.

### 2.1 Task Domain Model (`Task`)
The `Task` domain model represents a logical unit of work across its entire lifecycle:
- `taskId`: Immutable `UUID` uniquely identifying the task across the cluster.
- `taskType`: String identifying the executable task type (e.g. `DATA_PIPELINE`, `REPORT_GEN`).
- `payload`: Structured task payload string (JSON/text), decoupled from storage engines.
- `priority`: Explicit priority level (`LOW`, `NORMAL`, `HIGH`, `CRITICAL`).
- `status`: Lifecycle state governed strictly by the `TaskStateMachine`.
- `tenantId`: Identifies the owning tenant for multi-tenant isolation and scheduling quotas.
- `idempotencyKey`: Optional client-provided key ensuring deduplication of repeated submissions.
- `createdAt`: Timestamp when the task was initially instantiated.
- `scheduledAt`: Optional delayed execution timestamp.
- `deadline`: Optional absolute deadline after which the task cannot be dispatched.
- `timeout`: Optional maximum allowed execution duration for an individual attempt.
- `maxAttempts`: Maximum retry limit before transitioning to `DLQ` (default: 3).
- `currentAttempt`: Number of attempts executed so far (starts at 0).
- `version`: Optimistic concurrency counter incremented on every state transition.

### 2.2 Execution Attempt Model (`ExecutionAttempt`)
While a `Task` represents the logical unit of work, an `ExecutionAttempt` represents a single physical execution instance run by a specific worker node:
- `attemptId`: Unique identifier for the individual attempt.
- `taskId`: Foreign key referencing the parent `Task`.
- `workerId`: Identifier of the worker node executing this attempt.
- `attemptNumber`: Sequence number of the attempt (1, 2, ...).
- `status`: Attempt lifecycle status (`PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`).
- `startedAt`: Timestamp when worker execution commenced.
- `completedAt`: Timestamp when execution concluded (success, failure, or cancellation).
- `errorCode` / `errorMessage`: Failure diagnostics captured during worker execution.

### 2.3 Task Priority Model (`TaskPriority`)
- `LOW`: Priority weight 0 (background cleanup, non-urgent batch tasks).
- `NORMAL`: Priority weight 10 (standard operational tasks).
- `HIGH`: Priority weight 20 (interactive or time-sensitive tasks).
- `CRITICAL`: Priority weight 30 (system recovery, emergency remediation, SLA-critical tasks).

In Phase 07, the priority scheduler will combine this base weight with an aging factor to guarantee fair allocation and eliminate starvation under high concurrency.

### 2.4 Idempotency Contract
Multiple submissions carrying the same `idempotencyKey` within a tenant represent the same logical task submission. Network retries from clients or message brokers will resolve to the existing task record rather than creating duplicate execution workloads.

### 2.5 Optimistic Concurrency & Stale Update Protection
The `version` field on `Task` guards against lost updates and split-brain phenomena. Whenever a coordinator or worker attempts to advance state, the transition requires matching the expected version. In later phases, this couples with Redis epoch fencing tokens to guarantee that stale leaders or disconnected workers cannot overwrite newer cluster state.

---

## 3. Task State Machine & Lifecycle

The Task State Machine is side-effect free, deterministic, and independently testable without external dependencies.

### Plain Markdown Transition Diagram

```
           +-----------+
           |  CREATED  |
           +-----+-----+
                 |
                 | (submit)
                 v
           +-----------+               (cancel)
           |  QUEUED   +------------------------------------+
           +-----+-----+                                    |
                 |                                          |
                 | (dispatch to worker)                     |
                 v                                          |
           +-----------+               (cancel)             |
           |DISPATCHED +------------------------------------+
           +-----+-----+                                    |
                 |                                          |
                 | (worker start)                           |
                 v                                          |
           +-----------+               (cancel)             |
           |  RUNNING  +------------------------------------+
           +--+-----+--+                                    |
              |     |                                       |
    (success) |     | (failure)                             |
              v     v                                       |
+-----------+  +----+----+                                  |
| SUCCEEDED |  | FAILED  |                                  |
+-----------+  +-+-----+--+                                 |
 (TERMINAL)      |     |                                    |
                 |     | (attempts >= max)                  |
(attempts < max) |     v                                    |
                 |  +-------+                               |
                 |  |  DLQ  |                               |
                 |  +-------+                               v
                 | (TERMINAL)                         +-----------+
                 +----------------------------------->| CANCELLED |
                                                      +-----------+
                                                       (TERMINAL)
```

### State Definitions

| Status | Type | Description |
| :--- | :--- | :--- |
| `CREATED` | Initial | Instantiated and validated; ready for enqueueing. |
| `QUEUED` | Active | Waiting in persistent priority queue for worker allocation. |
| `DISPATCHED` | Active | Assigned to a specific worker; awaiting worker acknowledgement. |
| `RUNNING` | Active | Actively executing within a worker thread. |
| `SUCCEEDED` | Terminal | Completed successfully. No further transitions permitted. |
| `FAILED` | Transient | Attempt encountered error. Evaluates retry eligibility. |
| `CANCELLED` | Terminal | Explicitly cancelled by operator or deadline timeout. |
| `DLQ` | Terminal | Retry count exhausted; parked in Dead-Letter Queue for triage. |

---

## 4. Relationship to Future Infrastructure

- **PostgreSQL (Phase 03)**: Persists `Task` and `ExecutionAttempt` records with transactional version increments and unique constraints on `(tenantId, idempotencyKey)`.
- **Redis (Phase 04)**: Stores active queue sorted sets keyed by priority + scheduledAt, and worker lease locks.
- **gRPC (Phase 06)**: Uses `chronos_task_execution.proto` for streaming task assignments, results, and heartbeats.
