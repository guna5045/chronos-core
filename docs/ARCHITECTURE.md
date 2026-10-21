# Chronos Core - Architectural Specification

## Status
- **Current Phase**: Phase 03 (Persistence & Durable State)
- **Document Version**: 0.3.0

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

- **PostgreSQL (Phase 03)**: Authoritative durable source of truth for task state, execution history, append-only audit trail, and staged outbox events.
- **Redis (Phase 04)**: Distributed coordination, ephemeral worker locks, and lease-based leader election. Task authority remains strictly in PostgreSQL.
- **gRPC (Phase 06)**: Uses `chronos_task_execution.proto` for streaming task assignments, results, and heartbeats.

---

## 5. Durable Persistence Architecture (`chronos-coordinator`)

### 5.1 PostgreSQL as Authoritative Source of Truth
PostgreSQL 16+ is the single authoritative source of truth for all durable task state and execution history in Chronos Core.
Ephemeral cluster coordination systems (such as Redis in Phase 04) are strictly coordination aids and never hold authoritative task state.

### 5.2 Schema Architecture & Flyway Migrations
The persistence schema is version-controlled via Flyway migrations under `chronos-coordinator/src/main/resources/db/migration/`:

1. **`chronos_tasks` (`V1__create_tasks.sql`)**:
   - Primary entity table capturing the `Task` domain model.
   - Columns: `task_id` (UUID PK), `task_type`, `payload` (TEXT), `priority`, `status`, `tenant_id`, `idempotency_key`, `created_at`, `scheduled_at`, `deadline`, `timeout_ms`, `max_attempts`, `current_attempt`, `version`.
   - Partial Unique Index: `uq_chronos_tasks_tenant_idempotency` on `(tenant_id, idempotency_key)` WHERE `idempotency_key IS NOT NULL`.
   - Operational Indexes: `status`, `priority`, `scheduled_at`, `tenant_id`, `created_at`, `deadline`.
   - Queue Acquisition Index: Partial index `idx_chronos_tasks_queued` on `(priority, scheduled_at, created_at)` WHERE `status = 'QUEUED'` to accelerate future scheduler queue polling.

2. **`chronos_execution_attempts` (`V2__create_execution_attempts.sql`)**:
   - Durable physical execution history tracking every attempt made on worker nodes.
   - Foreign key referencing `chronos_tasks(task_id)` with `ON DELETE CASCADE`.
   - Unique constraint `uq_execution_attempts_task_attempt` on `(task_id, attempt_number)` preventing duplicate attempt numbering.
   - Indexes on `task_id`, `worker_id`, `status`, and `started_at`.
   - Preserves historical records across retries for failure triage and operational visibility.

3. **`chronos_task_audit_log` (`V3__create_task_audit_log.sql`)**:
   - Strictly append-only audit trail recording every task lifecycle state transition.
   - Captures `from_status`, `to_status`, `attempt_number`, `reason`, `metadata`, and `created_at`.
   - Never mutated or deleted under standard operations.

4. **`chronos_outbox_events` (`V4__create_outbox_events.sql`)**:
   - Durable transactional outbox table staging domain events for at-least-once publication.
   - Captures `aggregate_type`, `aggregate_id`, `event_type`, `payload`, `created_at`, `published_at`, and `attempt_count`.
   - Partial Index: `idx_chronos_outbox_pending` on `(created_at)` WHERE `published_at IS NULL`.

### 5.3 Explicit SQL & Repository Layer
Repository abstractions are implemented in `chronos-coordinator` using Spring `NamedParameterJdbcTemplate` with explicit SQL statements and row mappers:
- **`TaskRepository` / `JdbcTaskRepository`**: Explicit row mapping, optimistic locking version validation, and pessimistic locking via `findByIdForUpdate` (`SELECT ... FOR UPDATE`).
- **`ExecutionAttemptRepository` / `JdbcExecutionAttemptRepository`**: History preservation and attempt lookup by task and sequence.
- **`TaskAuditRepository` / `JdbcTaskAuditRepository`**: Append-only log recording.
- **`OutboxRepository` / `JdbcOutboxRepository`**: Event staging, pending retrieval, and publication status marking.

Pure domain entities in `chronos-common` remain 100% free of Spring or JPA/ORM annotations.

### 5.4 Transaction Boundaries & Atomicity
The `TransactionalTaskService` manages ACID transaction boundaries across domain updates:

```
BEGIN TRANSACTION
  1. Read current task state and expected version
  2. Validate requested state transition via TaskStateMachine
  3. Update task row checking: WHERE task_id = ? AND version = ?
  4. Append immutable audit log record to chronos_task_audit_log
  5. Stage corresponding outbox event in chronos_outbox_events
COMMIT
```

If any step fails (e.g., state machine validation failure, optimistic locking conflict, or storage error), the entire transaction rolls back cleanly. Neither partial state changes nor dangling outbox events can be committed.

### 5.5 Optimistic Concurrency vs. Distributed Fencing Tokens
- **`Task.version`**: Pure database/domain optimistic concurrency version counter incremented on each state change (`version = version + 1`). Guards against concurrent lost updates in PostgreSQL.
- **Distributed Fencing Tokens (Phase 04)**: Monotonically increasing epoch tokens issued by cluster coordination leases (Redis/ZooKeeper) to reject stale leader/worker operations.
- **Architectural Guarantee**: Database optimistic versions and distributed coordination fencing tokens are independent concepts and must never be conflated.

### 5.6 Payload Design Decision
Task payloads are persisted as `TEXT NOT NULL`.
Rationale:
- Maintains decoupling between the pure domain entity and database-specific dialect types.
- Supports arbitrary client payloads (JSON, text, XML, or encoded formats) without requiring dialect conversion in JDBC.
- Avoids classpath coupling to PostgreSQL JSONB extensions in the core repository interface.

### 5.7 Transactional Outbox Pattern
The transactional outbox pattern guarantees at-least-once event publication without dual-write consistency hazards:
- When a task is created or transitioned, the corresponding outbox event is inserted in the exact same database transaction.
- An application or worker crash immediately after a state update will not result in lost events.
- Asynchronous outbox dispatching to message brokers (e.g., Kafka) will be introduced in subsequent phases; no external broker is connected in Phase 03.

