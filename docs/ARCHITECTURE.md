# Chronos Core - Architectural Specification

## Status
- **Current Phase**: Phase 10 (REST API & Security Layer)
- **Document Version**: 0.10.0

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

---

## 6. Distributed Coordination Layer (`chronos-coordinator`)

Phase 04 introduces the distributed coordination infrastructure required for multi-coordinator deployments.

> **Architectural Precedence & Distributed Systems Truth**:  
> Chronos Core uses lease-based leader election with fencing tokens. It is not a consensus protocol such as Raft or Paxos. Do not describe Redis lease-based leader election as "consensus-backed" or "Raft/Paxos".  
> Furthermore, Redis acts solely as the ephemeral coordination and lock store. PostgreSQL remains the single durable source of truth for task state and execution history.

### 6.1 Lease-Based Leader Election Lifecycle

In a multi-coordinator cluster, exactly one coordinator instance acts as the active `LEADER` responsible for cluster orchestration, while other instances operate as `FOLLOWER` standbys ready for failover.

#### Normal Operational Cycle
```
Coordinator
     ↓
Acquire Redis lease (`SET chronos:leader:lock <coordinator-id> NX EX 5`)
     ↓
Leader (Assigned monotonically incremented epoch: `INCR chronos:leader:epoch`)
     ↓
Renew lease periodically via atomic Lua script (every 1.5s)
     ↓
Leadership maintained
```

#### Lease Expiry & Failover Cycle
```
Coordinator A (Leader, Epoch 1)
     ↓
Network pause / GC pause / crash
     ↓
Redis lease TTL expires (5 seconds)
     ↓
Coordinator B acquires lease (`SET NX EX 5`)
     ↓
New leader epoch incremented (Epoch 2)
     ↓
Coordinator A resumes from pause
     ↓
Attempted renewal/release rejected by Redis Lua check
     ↓
Coordinator A detects ownership loss -> transitions to FOLLOWER
```

### 6.2 Fencing Tokens & Stale Leader Protection

A fundamental distributed systems vulnerability in lease-based systems is the **stale leader problem**: a coordinator that experiences a stop-the-world garbage collection pause or network blip may have its lease expire while it is paused. When it resumes, it might believe it is still the leader and attempt state-modifying operations against downstream storage.

To neutralize this hazard:
1. **Generational Epoch Fencing**: Every time a coordinator acquires leadership, it executes an atomic `INCR chronos:leader:epoch`. This provides a monotonically increasing integer epoch.
2. **Epoch Verification**: All downstream coordination, dispatch, and state-modification operations check the leader epoch against the latest cluster epoch. Any operation carrying a stale epoch (`epoch < currentClusterEpoch`) is immediately rejected with a `StaleLeaderException`.
3. **Task.version vs. Leader Epoch Distinction**:
   - `Task.version`: Database optimistic concurrency version on individual PostgreSQL task rows. Guards against concurrent lost updates on specific tasks.
   - `leaderEpoch`: Cluster-wide generation counter protecting leadership terms from zombie or stale coordinators.
   - **These two mechanisms serve distinct orthogonal concerns and are never conflated.**

### 6.3 Distributed Task Locks

To protect individual tasks during scheduling, state transitions, and worker assignment across multiple coordinator nodes, Chronos Core implements a reusable distributed lock abstraction:

- **Key Namespace**: `chronos:task:lock:{resourceId}` (constructed centrally via `RedisKeyFactory`).
- **Owner Token**: A cryptographic UUID generated per acquisition (`UUID.randomUUID().toString()`). Never rely solely on coordinator ID, ensuring distinct attempts by the same coordinator remain isolated.
- **Bounded TTL**: Locks are strictly time-bounded (default: 30 seconds, configurable via `chronos.coordination.task-lock-ttl`) to guarantee automatic reclamation if a holding node crashes.
- **Atomic Acquisition**: Uses Redis `SET key ownerToken NX EX ttl` in a single command, eliminating the split-second race condition inherent in separate `SETNX` + `EXPIRE`.
- **Atomic Safe Release via Lua Script**:
  ```lua
  if redis.call('get', KEYS[1]) == ARGV[1] then
      return redis.call('del', KEYS[1])
  else
      return 0
  end
  ```
  This prevents a stale worker or coordinator that suffered a delay from inadvertently deleting a lock that has expired and been acquired by another node.
- **Atomic Lease Extension via Lua Script**:
  ```lua
  if redis.call('get', KEYS[1]) == ARGV[1] then
      return redis.call('expire', KEYS[1], ARGV[2])
  else
      return 0
  end
  ```

### 6.4 Coordination State Model

Coordinator nodes deterministically transition across three explicit states:
- `FOLLOWER`: Standby coordinator monitoring the cluster and periodically attempting leadership acquisition.
- `LEADER`: Active coordinator holding the unexpired Redis lease, maintaining periodic renewals, and issuing fencing tokens.
- `LEADERSHIP_LOST`: Intermediate event state triggered when renewal fails or ownership is lost. Cleanly notifies listeners to abort leader-only work before returning to `FOLLOWER`.

```
  ┌──────────┐      acquire lease       ┌──────────┐
  │ FOLLOWER │ ───────────────────────► │  LEADER  │
  └──────────┘                          └────┬─────┘
       ▲                                     │ renewal failure /
       │               transition            │ ownership loss
       └─────────────────────────────────────▼
                                      ┌───────────────┐
                                      │LEADERSHIP_LOST│
                                      └───────────────┘
```

### 6.5 Correctness Guarantees & Non-Claims

Chronos Core explicitly declares the following execution semantics:
- **No False Claims**: We do **not** claim "exactly-once execution" or "distributed consensus".
- **Semantic Model**: Chronos Core provides **at-least-once delivery with idempotency and fencing-based stale-write protection**.
- **No In-Memory Single-Node Fallbacks**: If Redis coordination infrastructure is unavailable, coordinators refuse to silently operate as a fake single-node cluster; they fail fast and log operational diagnostics.

---

## 7. Worker Cluster & gRPC Communication Architecture

### 7.1 Separation of Concerns
Phase 05 establishes the distributed worker execution tier. The architecture strictly isolates responsibilities:
- **Coordinator**: Maintains cluster topology, in-memory worker registry, failure detection, task dispatch decisions, and durable PostgreSQL state transitions.
- **Worker**: Manages local task concurrency via Java 21 virtual threads, reports heartbeats over persistent HTTP/2 streams, and delivers execution outcomes back to the Coordinator.
- **Zero Database Exposure**: Workers have no direct access to PostgreSQL or Redis. All state transitions flow through gRPC contracts.

### 7.2 gRPC Protocol Topology
The network contract consists of two distinct gRPC services defined in `chronos_task_execution.proto`:

1. **`TaskCoordinationService`** (Hosted on Coordinator, default port 9090):
   - `RegisterWorker`: Synchronous registration handshake returning unique session IDs.
   - `StreamHeartbeat`: Bidirectional HTTP/2 streaming ping/pong every 1000ms.
   - `UpdateTaskState`: In-flight attempt status notifications.
   - `ReportTaskResult`: Terminal attempt outcome reporting (`SUCCEEDED`, `FAILED`, `CANCELLED`).
2. **`WorkerService`** (Hosted on Worker nodes, default port 9091+):
   - `DispatchTask`: Non-blocking task assignment with explicit call deadlines.
   - `CancelTask`: Preemption and interruption of active task attempts.

```
       COORDINATOR                               WORKER
            │                                       │
            │ <────── RegisterWorker (Unary) ───────│
            │                                       │
            │ <═══ StreamHeartbeat (Bidi Stream) ══>│
            │                                       │
            │ ──────── DispatchTask (Unary) ──────> │
            │                                       │
            │ <────── ReportTaskResult (Unary) ─────│
```

### 7.3 In-Memory Worker Registry & Session Lifecycle
- Worker metadata and liveness are maintained in-memory within `InMemoryWorkerRegistry` on the active Coordinator.
- High-frequency heartbeat writes are strictly prohibited from touching Redis or PostgreSQL.
- Each registration assigns a cryptographically unique `sessionId` (UUID v4). Any message bearing an unrecognized or outdated `sessionId` is rejected immediately with a `STALE_SESSION` error, protecting against delayed or zombie worker interactions.

### 7.4 Liveness Detection & Health State Machine
The Coordinator runs `WorkerHealthMonitor` every 1,000ms to evaluate worker liveness:
- `ACTIVE`: Heartbeat received within 3,000ms (`worker-suspect-timeout-ms`).
- `SUSPECTED`: Heartbeat absent for > 3,000ms. Worker is marked ineligible for new task dispatch.
- **Recovery**: Receipt of a valid heartbeat ping automatically restores a `SUSPECTED` worker to `ACTIVE`.
- `REMOVED`: Heartbeat absent for > 6,000ms (`worker-eviction-timeout-ms`). Worker is evicted from memory, triggering failure handling for any assigned tasks.

### 7.5 Virtual Thread Execution Engine
Worker nodes leverage Java 21 Virtual Threads (`VirtualThreadTaskExecutor`):
- Provides unbounded lightweight concurrency for I/O-intensive task payloads.
- Bounded capacity gating via atomic CAS counters (`activeTasks.getAndUpdate(...)`) up to `maxConcurrency`.
- Cooperative cancellation through thread interruption and atomic cancellation flags.

---

## 8. Distributed Scheduling & Task Distribution Architecture (`chronos-coordinator`)

Phase 06 establishes the distributed scheduling and task distribution engine connecting PostgreSQL durable state, Redis coordination, worker registry membership, consistent hashing, and gRPC dispatch.

### 8.1 Architectural Flow & Separation of Concerns

```
                     TASK SUBMITTED
                           │
                           ▼
                      PostgreSQL
                     Durable State
                           │
                           ▼
                    Task Scheduler
                           │
              ┌────────────┴────────────┐
              │                         │
              ▼                         ▼
         Ready Queue               Scheduling Rules
         / Selection               Priority / Time
              │
              ▼
        Worker Selection
              │
              ▼
      Consistent Hash Ring
              │
              ▼
       Worker A / B / C
              │
             gRPC
              │
              ▼
          EXECUTION
```

The system strictly enforces tier isolation:
- **PostgreSQL**: Authoritative durable storage for task state, audit records, and transactional outbox events. The scheduler never bypasses PostgreSQL.
- **Redis**: Ephemeral leader leases, monotonically increasing fencing epoch counters, and per-task distributed coordination locks.
- **In-Memory Coordinator Registry**: Real-time worker node membership, liveness states, and capacity tracking.
- **gRPC (`chronos_task_execution.proto`)**: Binary RPC dispatch to worker nodes.

### 8.2 Leader-Aware Scheduling & Fencing Protection

Only the coordinator node currently holding the active cluster leadership lease executes the scheduling loop:
- `LeaderAwareTaskScheduler` continuously monitors `LeaderElectionService.isLeader()`. Follower coordinators remain completely dormant in passive standby.
- The scheduler subscribes to `LeadershipStateListener` callbacks. Upon lease expiration, GC pause, or ownership loss, `onLeadershipLost()` immediately flips the active execution flag and cancels in-flight scheduling loops.
- Before every dispatch iteration, the scheduler asserts the active leader epoch with `FencingTokenService.validateFencingToken(...)`. If another coordinator has claimed leadership or incremented the cluster epoch, scheduling halts immediately with a `StaleLeaderException`.

### 8.3 PostgreSQL Ready-Task Selection & Fairness Policy

The scheduler selects pending work from PostgreSQL in bounded batches (default: 50 tasks per poll) using explicit transactional queries:
- **Schedulable Task Query**:
  ```sql
  SELECT task_id, task_type, payload, priority, status, tenant_id, idempotency_key,
         created_at, scheduled_at, deadline, timeout_ms, max_attempts, current_attempt, version
  FROM chronos_tasks
  WHERE status = 'QUEUED'
    AND (scheduled_at IS NULL OR scheduled_at <= :asOf)
  ORDER BY priority DESC,
           scheduled_at ASC NULLS FIRST,
           created_at ASC
  LIMIT :limit
  ```
- **Anti-Starvation Aging Policy**:
  To prevent continuous streams of `CRITICAL` and `HIGH` tasks from indefinitely starving `NORMAL` and `LOW` tasks, `SchedulingPolicy` reserves a configurable portion of each batch (default: 20%) for starved tasks identified by:
  ```sql
  SELECT ...
  FROM chronos_tasks
  WHERE status = 'QUEUED'
    AND (scheduled_at IS NULL OR scheduled_at <= :asOf)
    AND created_at <= :starvationCutoff
  ORDER BY created_at ASC
  LIMIT :limit
  ```
- Starved tasks take precedence in the dispatch batch, preserving fairness without sacrificing priority guarantees.

### 8.4 Scheduled-At & Deadline Pre-Dispatch Enforcement

- **Scheduled-At Gate**: Tasks configured with a future `scheduledAt` timestamp are filtered at the database query layer (`scheduled_at <= :asOf`). They safely remain `QUEUED` until their scheduled time arrives.
- **Deadline Awareness**: Prior to dispatch, the scheduler evaluates whether `task.getDeadline()` has expired against current wall-clock time (`now > deadline`). If expired, the task is **not** dispatched to a worker; instead, it is atomically transitioned to `CANCELLED` via `TransactionalTaskService.transitionTask(..., TaskStatus.CANCELLED, "Task deadline exceeded prior to dispatch", ...)`.

### 8.5 Distributed Task Locking & Transaction Boundaries

To prevent competing coordinator instances or concurrent loops from attempting to dispatch the same task, the scheduler employs distributed task locks:
- **Lock Acquisition**: Before processing, the scheduler acquires a Redis lock via `DistributedLockService.tryAcquire("task:" + taskId, lockTtl)`.
- **Lock Scope**: If lock acquisition fails (task already locked by another thread), the task is skipped in this iteration.
- **Decoupled Transaction Boundary**:
  - The Redis lock is acquired *outside* the PostgreSQL transaction.
  - The worker gRPC dispatch RPC is executed *outside* the database transaction.
  - The database transaction (`TransactionalTaskService.startExecutionAttempt`) runs atomically *after* the worker responds with an acceptance.
  - The Redis lock is unconditionally released in a `finally` block upon completion or failure.
  - This architecture ensures that network latencies or worker dispatch delays never hold open database connections, preventing connection pool exhaustion and deadlocks.

### 8.6 Consistent Hash Ring Architecture

Deterministic worker assignment is provided by `ConsistentHashRing`:
- **Virtual Nodes**: Each worker is assigned 128 virtual nodes (e.g. `worker-1#0`, `worker-1#1`, ... `worker-1#127`).
- **Distribution Hash**: Uses MD5 as a fast, uniform, deterministic non-cryptographic distribution hash function mapping worker nodes and task IDs to a 64-bit integer ring.
- **Data Structure**: `ConcurrentSkipListMap<Long, String>` stores ring tokens for thread-safe lock-free ceiling queries.
- **Lookup & Wrap-Around**: Given `hash(taskId.toString())`, `ring.ceilingEntry(hash)` finds the first worker clockwise on the ring. If the hash exceeds the maximum ring token, the lookup seamlessly wraps around to `ring.firstEntry()`.
- **Minimal Remapping**: Adding or removing a worker remaps only `1/N` of keys on average, leaving the remaining cluster mappings undisturbed.

### 8.7 Worker Selection, Capacity Gating & Backpressure

- **Worker Eligibility**: Only worker nodes in `WorkerState.ACTIVE` participate in the hash ring. Nodes in `REGISTERING`, `SUSPECTED`, or `REMOVED` states are excluded from dispatch.
- **Capacity Verification**: Before dispatching to the primary hash ring candidate, the selector verifies `worker.hasAvailableCapacity()`.
- **Candidate Fallback**: If the primary candidate is saturated (`activeTasks >= maxConcurrency`), the selector iterates along the ring (`ring.getCandidates(key)`) to find the next active worker with capacity.
- **Admission Backpressure**: If all eligible active workers are saturated, dispatch is safely aborted (`NO_WORKER_AVAILABLE`). The task remains securely stored in PostgreSQL in `QUEUED` state, and the scheduler will poll it again on its next iteration without tight-loop busy-waiting or memory bloat.

### 8.8 gRPC Task Dispatch & Atomic State Transition

When an eligible worker is selected:
1. `WorkerDispatchClient.dispatchTask(worker, assignment)` delivers the protobuf assignment over gRPC with a 5-second deadline.
2. If the worker accepts (`accepted == true`):
   - `TransactionalTaskService.startExecutionAttempt(taskId, workerId, attemptNumber)` atomically commits:
     - Task status transition: `QUEUED -> DISPATCHED -> RUNNING`.
     - Creation of `ExecutionAttempt` record.
     - Append to `chronos_task_audit_log`.
     - Staged outbox event `TaskStarted` in `chronos_outbox_events`.
3. If the worker rejects (`CAPACITY_EXHAUSTED` or error):
   - The task is not transitioned; it remains `QUEUED` in PostgreSQL.
   - The distributed lock is released, allowing subsequent scheduler loops to re-evaluate it.

### 8.9 Semantic Guarantee

Chronos Core provides **at-least-once delivery with idempotency, transactional state transitions, distributed locking, and fencing-based stale-operation protection**. It does not claim exactly-once execution.

---

## 9. Fault Tolerance, Retries, Execution Leases & Recovery Architecture

Phase 07 establishes the end-to-end fault tolerance and recovery subsystem ensuring that tasks survive worker node crashes, network partitions, dispatch dropouts, execution timeouts, and retryable failures without duplicate active executions or lost state.

### 9.1 Retry Policy & Failure Classification

Failure handling distinguishes transient infrastructure glitches from terminal validation errors:
- **`FailureClassification`**:
  - **Retryable Failures**: `WORKER_UNAVAILABLE`, `WORKER_TIMEOUT`, `TEMPORARY_NETWORK_FAILURE`, `CAPACITY_EXHAUSTED`, `EXECUTION_TIMEOUT`, `TRANSIENT_INFRASTRUCTURE_ERROR`.
  - **Non-Retryable Failures**: `INVALID_TASK`, `INVALID_PAYLOAD`, `UNSUPPORTED_TASK_TYPE`, `VALIDATION_ERROR`, `PERMISSION_ERROR`.
- **`RetryPolicy`**:
  - Re-evaluates each failed attempt against task `maxAttempts` and error classification.
  - If retryable and `currentAttempt < maxAttempts`: computes next retry delay, updates `scheduledAt = now + delay`, and transitions task back to `QUEUED`.
  - If non-retryable or `currentAttempt >= maxAttempts`: transitions task directly to `DLQ` (Dead-Letter Queue).

### 9.2 Exponential Backoff with Jitter

To prevent retry storms where dozens of failed tasks all retry simultaneously, retry delays follow a bounded exponential progression:
```text
delay = min(maxDelay, baseDelay × 2^(attempt - 1))
```
- **Base Delay**: Configurable (default: 1s).
- **Max Delay**: Configurable upper bound preventing unbounded backoff (default: 60s).
- **Jitter Strategies**:
  - `NONE`: Deterministic exponential delay.
  - `FULL`: Uniform random delay between `0` and calculated exponential delay.
  - `DECORRELATED`: Random delay between `baseDelay` and `3 × previousDelay`.

### 9.3 Task Execution Leases & Lease Lifecycle

Chronos Core separates **distributed scheduling locks** (which prevent competing coordinators from simultaneously dispatching a queued task) from **task execution leases** (which represent durable ownership of an active execution attempt):
- **Lease Model (`TaskExecutionLease`)**:
  - Tracks `taskId`, `attemptId`, `workerId`, `fenceToken` (matching coordinator leader epoch), `acquiredAt`, and `expiresAt`.
  - Stored in durable table `chronos_task_leases` with unique constraint on `task_id`.
- **Lease Lifecycle**:
  - **Acquisition**: `ExecutionLeaseService.acquireLease(...)` acquires an exclusive lease upon successful dispatch.
  - **Renewal**: Workers or active execution monitors periodically renew the lease (`renewLease(...)`).
  - **Release**: Upon terminal completion (`SUCCEEDED`, `FAILED`, `DLQ`), `releaseLease(taskId)` removes the lease.
  - **Expiration**: If a worker abruptly dies, its lease ceases to renew and naturally expires (`expires_at <= :asOf`).

### 9.4 Worker Failure Recovery & Eviction Integration

When `WorkerHealthMonitor` detects that a worker node has stopped sending heartbeats (>6,000ms), it transitions the node to `WorkerState.REMOVED` and immediately notifies `TaskRecoveryService.recoverWorkerFailure(workerId)`:
1. Identifies all active execution leases owned by the failed worker.
2. For each task, checks durable database status and optimistic lock version.
3. If still `DISPATCHED` or `RUNNING`: marks attempt as failed with `WORKER_UNAVAILABLE`.
4. Evaluates `RetryPolicy`: either schedules retry with exponential backoff (`QUEUED` with future `scheduledAt`) or transitions to `DLQ`.
5. Cleans up orphaned leases.

### 9.5 Stale Worker Execution Protection & Fencing

To guard against network partitions where a worker experiences a long pause or partition, gets evicted by the coordinator, has its task reassigned to another worker, and later attempts to report an outdated completion:
- **Attempt Sequence Validation**: The coordinator strictly matches reported `attemptId` and `attemptNumber` against `task.getCurrentAttempt()`.
- **Fence Token Assertion**: Compares the attempt's fence token against active leader epoch.
- **Terminal Task Invariance**: If a task is already in `SUCCEEDED` or `DLQ`, or if a newer attempt has started, any stale result from the previous attempt is **strictly rejected**.
- **Audit Logging**: Every rejected stale report is recorded in `chronos_task_audit_log` with event type `STALE_RESULT_REJECTED`.

### 9.6 Task Timeouts & Cooperative Cancellation

Tasks configured with an execution `timeout` (e.g. 30s) are actively tracked:
1. `TaskTimeoutService.scanForTimeouts()` detects running tasks where `now - attempt.startedAt > timeout`.
2. Dispatches a cooperative cancellation gRPC request (`WorkerDispatchClient.cancelTask`) to the assigned worker node, interrupting its virtual execution thread.
3. Fails the attempt with `EXECUTION_TIMEOUT`.
4. Applies `RetryPolicy` to determine whether to requeue for a subsequent attempt or route to `DLQ`.

### 9.7 Dead-Letter Queue (DLQ) & DLQ Replay Semantics

Tasks that fail permanently or exhaust their `maxAttempts` are routed to `TaskStatus.DLQ`:
- **Preserved History**: The full failure history, error code, error message, and all previous execution attempts remain permanently recorded in PostgreSQL.
- **`DlqService.replayTask(taskId)`**:
  - Validates that the task is currently in `DLQ`.
  - Atomically resets `currentAttempt` to 0, clears error metadata and execution timestamps, and transitions state `DLQ -> QUEUED`.
  - Stages a `TaskReplayed` audit record and outbox event.
  - The task becomes immediately eligible for standard priority scheduling in Phase 06.

### 9.8 Background Recovery Scheduler & Concurrency Control

- **`RecoveryScheduler`**: Runs periodically on the active coordinator leader node (default poll: 1,000ms, batch size: 50).
- **Safe Recovery Operations**:
  - Scans for expired execution leases (`reclaimExpiredLeases`).
  - Scans for timed-out running attempts (`scanForTimeouts`).
- **Concurrent Recovery Protection**:
  - If multiple coordinators or concurrent threads attempt recovery on the same task, PostgreSQL optimistic locking (`WHERE task_id = ? AND version = ?`) ensures exactly one transaction commits the transition.
  - Competing transactions detect the version increment, abort without corruption, and log an info message (`Concurrent recovery detected; skipping`).

### 9.9 Delivery Guarantee Summary

Chronos Core guarantees:
> **At-least-once delivery with idempotency, durable state transitions, task execution leases, retry/backoff, fencing-based stale execution protection, worker failure detection, and dead-letter handling.**

Chronos Core does **NOT** provide, nor does it claim, exactly-once execution.

---

## 10. Workflow & DAG Orchestration Engine (`chronos-coordinator::workflow`)

Phase 08 introduces the workflow orchestration layer for directed acyclic graph (DAG) task composition without duplicating the underlying execution scheduler.

```
Workflow Definition
        │
        ▼
Workflow Version (Immutable)
        │
        ▼
Workflow Execution (Instance)
        │
        ▼
Dependency Engine
        │ (activates eligible nodes)
        ▼
Eligible Chronos Tasks (QUEUED)
        │
        ▼
Phase 06 Scheduler
        │
        ▼
Consistent Hashing & Capacity Tracking
        │
        ▼
gRPC Virtual-Thread Workers
        │
        ▼
Phase 07 Recovery & Retries
        │ (on logical success / terminal DLQ)
        ▼
Workflow State Update & Downstream Activation
```

### 10.1 Key Guarantees & Architectural Boundaries
1. **No Duplicate Execution Engine**: Workflow nodes materialize as standard `Task` entities with `TaskStatus.QUEUED`. The existing Phase 06 leader-aware scheduler dispatches them, and Phase 07 monitors their execution leases.
2. **Deterministic DAG Validation**: Cycle detection via DFS 3-coloring and deterministic topological ordering via Kahn's algorithm with lexicographical tie-breaking (`WorkflowDagValidator`).
3. **Immutable Workflow Versions**: Once published, workflow definitions and edges cannot be modified. New iterations must be published as distinct versions.
4. **Execution Version Pinning**: Running executions are permanently bound to their starting `versionId`, unaffected by subsequent version publications.
5. **Atomic Compare-and-Swap Activation**: `claimNodeForActivation` uses row-level database conditional updates (`UPDATE ... WHERE status = 'PENDING'`) to guarantee that concurrent dependency completions activate downstream nodes exactly once.
6. **Deterministic Failure Propagation**: Terminal task failure (`DLQ` or unrecoverable error) marks all downstream descendant nodes as `SKIPPED` and transitions the workflow execution to `FAILED`.
7. **Cooperative Cancellation**: Workflow cancellation marks non-terminal nodes `CANCELLED` and issues cooperative gRPC cancellation to active worker nodes via `WorkerDispatchClient`.

---

## 11. Advanced Platform Capabilities (`chronos-coordinator::policy`)

Phase 09 establishes multi-tenant isolation, admission controls, fairness, quotas, rate limiting, and execution policies across the existing task and workflow pipelines.

```text
                  TASK SUBMISSION
                         │
                         ▼
                ┌─────────────────┐
                │ Admission       │
                │ Control         │
                └────────┬────────┘
                         │
          ┌──────────────┼───────────────┐
          ▼              ▼               ▼
     Tenant Limits   Rate Limit     System Backpressure
          │              │               │
          └──────────────┼───────────────┘
                         ▼
                   PostgreSQL
                         │
                         ▼
                    Scheduler
                         │
                ┌────────┴────────┐
                ▼                 ▼
          Fairness Policy    Priority/Aging
                │                 │
                └────────┬────────┘
                         ▼
                  Worker Selection
                         │
                         ▼
                       Worker
```

### 11.1 Key Architectural Components
1. **`TaskAdmissionController`**: Evaluates submission boundary (`evaluateSubmission` for tenant queue limits) and scheduling boundary (`evaluateScheduling` for tenant concurrency, rate limits, concurrency keys, and worker saturation) returning explicit `AdmissionDecision` instances.
2. **`TenantRateLimiter`**: Token bucket rate limiting (`tasksPerSecond`, `burstCapacity`) backed by atomic Redis Lua scripts (`chronos:tenant:{tenantId}:rate`) with automatic TTL expiration and fail-open resilience during transient Redis outages.
3. **`TenantConcurrencyManager`**: Atomic Redis Set Lua check-and-add (`chronos:tenant:{tenantId}:running`), fail-closed PostgreSQL fallback, leak-free release on all completion/timeout/cancellation paths, and durable failover reconciliation (`reconcileWithDurableState`).
4. **`SchedulingPolicy` & Tenant Fairness**: Interleaved round-robin candidate selection across per-tenant queues balanced with priority-aging anti-starvation mechanisms.
5. **Logical Concurrency Keys (`concurrencyKey`)**: Per-task logical serialization keys with distributed lease-aware locks.
7. **Hardened DLQ Management**: Inspection (`inspectTask`), durable archival marker (`archiveTask`), and safe replay (`replayTask`) that re-activates downstream workflow nodes without creating duplicate executions.

---

## 12. REST API & Security Architecture (Phase 10)

Phase 10 exposes Chronos Core capabilities through a versioned, validated, and secured REST API layer without duplicating domain, scheduling, or recovery logic.

```text
                    CLIENT
                       │
                       ▼
             ┌──────────────────┐
             │ REST API /v1     │
             │ Spring Security  │
             └────────┬─────────┘
                      │
             ┌────────┴─────────┐
             │ Authentication   │
             │ Authorization    │
             │ Tenant Context   │
             │ Validation       │
             └────────┬─────────┘
                      │
                      ▼
             Application Services
                      │
          ┌───────────┼───────────┐
          ▼           ▼           ▼
        Tasks      Workflows     DLQ
          │           │           │
          └───────────┼───────────┘
                      ▼
              Existing Chronos Core
```

### 12.1 Key Design Principles
1. **Delegation, Not Duplication**: REST controllers strictly orchestrate HTTP inputs and delegate business operations directly to existing services (`TaskSubmissionService`, `TransactionalTaskService`, `WorkflowService`, `WorkflowExecutionService`, `DlqService`, `WorkerRegistry`, `TenantPolicyService`).
2. **Stateless Security**: Spring Security configured with `SessionCreationPolicy.STATELESS` and OAuth2 Resource Server validating HMAC-SHA256 / RSA signed JSON Web Tokens (JWT).
3. **Role-Based Access Control (RBAC)**:
   - `ROLE_VIEWER`: Read-only visibility across tasks, workflows, workers, cluster, DLQ, and policies.
   - `ROLE_OPERATOR`: Operational mutations including task submission, retry, cancellation, workflow execution, DLQ replay, and archival.
   - `ROLE_ADMIN`: Platform governance including workflow version drafting, publishing, deprecation, and tenant execution policy updates.
4. **Tenant Isolation & IDOR Protection**: Server-side tenant assertion (`TenantSecurityContext.assertCanAccessTenant`) ensures a tenant token cannot view or mutate another tenant's tasks, workflow executions, DLQ entries, or policies.
5. **REST Idempotency**: Support for `Idempotency-Key` HTTP header mapping directly to the underlying `(tenantId, idempotencyKey)` partial unique index in PostgreSQL.
6. **Centralized Error Translation**: `GlobalExceptionHandler` converts domain exceptions into consistent `ApiErrorResponse` structures without leaking database or coordination internals.

