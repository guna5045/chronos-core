# Chronos Core &mdash; Phase 09: Advanced Platform Capabilities

## 1. Overview & Architectural Pipeline

Phase 09 strengthens Chronos Core into a multi-tenant distributed task orchestration platform. It enforces strict admission boundaries, deterministic fairness, per-tenant quotas, token bucket rate limits, atomic concurrency accounting, logical concurrency keys, hardened cancellation, and auditable DLQ management without creating duplicate schedulers, queues, or recovery subsystems.

### The Unified Execution Pipeline

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

---

## 2. Tenant Execution Policy & Durable Configuration

Tenants represent the foundational execution and resource isolation boundary in Chronos Core. Tenant quotas and rate limits are defined via `TenantExecutionPolicy` and durably stored in the `chronos_tenant_policies` PostgreSQL table (Flyway migration `V7__create_advanced_platform_capabilities.sql`).

### Schema Definition (`chronos_tenant_policies`)

| Column | Type | Default | Constraint / Invariant |
| :--- | :--- | :--- | :--- |
| `tenant_id` | `VARCHAR(128)` | PK | Non-null, non-blank tenant identifier |
| `max_concurrent_tasks` | `INTEGER` | 100 | `> 0` |
| `max_queued_tasks` | `INTEGER` | 10,000 | `> 0` |
| `tasks_per_second` | `DOUBLE PRECISION` | 100.0 | `> 0.0` |
| `burst_capacity` | `INTEGER` | 200 | `>= 1` |
| `max_workflow_executions`| `INTEGER` | 50 | `> 0` |
| `created_at` | `TIMESTAMPTZ` | &mdash; | Non-null creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | &mdash; | Non-null last update timestamp |

If no specific record exists for a tenant, the engine falls back to deterministic cluster defaults (`ClusterPolicyDefaults`), ensuring zero configuration overhead for standard tenants while permitting per-tenant policy overrides.

---

## 3. Admission Control (`TaskAdmissionController`)

Admission control operates at two deterministic boundaries:
1. **Submission Boundary (`evaluateSubmission`)**: Evaluated synchronously when tasks are submitted to `TransactionalTaskService`. Enforces tenant queue limits (`maxQueuedTasks`) and policy validity before any row is written to PostgreSQL.
2. **Scheduling Boundary (`evaluateScheduling`)**: Evaluated periodically by the scheduler tick before dispatching tasks to workers. Enforces tenant concurrency limits, token bucket rate limits, logical concurrency key exclusion, and cluster-wide worker backpressure.

### Explicit Admission Decisions (`AdmissionStatus`)

Chronos Core avoids boolean-only admission checks. Every evaluation returns a structured `AdmissionDecision`:

| Status | Boundary | Semantic Description | Task Lifecycle Consequence |
| :--- | :--- | :--- | :--- |
| `ADMITTED` | Both | Task meets all policy constraints, quotas, and worker capacity requirements. | Task is persisted (at submission) or dispatched to worker (at scheduling). |
| `TENANT_QUEUE_LIMIT` | Submission | Tenant's total active queued tasks (`QUEUED` status) exceeds `maxQueuedTasks`. | Submission rejected immediately with `TenantQueueLimitExceededException`. No orphan rows created. |
| `TENANT_CONCURRENCY_LIMIT` | Scheduling | Tenant currently has `maxConcurrentTasks` tasks in active execution (`DISPATCHED` or `RUNNING`). | Task **remains `QUEUED`**. Not failed; not sent to DLQ; no fake attempt recorded. Retried on subsequent scheduler tick. |
| `RATE_LIMITED` | Scheduling | Tenant has exhausted token bucket admission budget for the current window. | Task **remains `QUEUED`**. Not failed; not sent to DLQ. Will be evaluated as token bucket refills. |
| `CONCURRENCY_KEY_BLOCKED`| Scheduling | Another task with the same `(tenantId, concurrencyKey)` is currently running or locked. | Task **remains `QUEUED`**. Preserves mutual exclusion between conflicting tasks. |
| `SYSTEM_BACKPRESSURE` | Scheduling | Cluster has 0 registered workers or all worker execution slots are saturated. | Task **remains `QUEUED`**. Dispatches resume automatically when worker capacity frees up. |
| `INVALID_POLICY` | Both | Tenant identifier is blank or policy limits contain invalid/unsupported parameters. | Call rejected immediately. |

---

## 4. Tenant Fairness & Scheduling Anti-Starvation

### The Problem
If Tenant A submits 10,000 tasks and Tenant B submits 10 tasks, an unconstrained priority or FIFO scheduler could exhaust all worker dispatch slots on Tenant A, indefinitely starving Tenant B.

### The Algorithm: Tenant-Aware Round-Robin Fair Queueing with Aging Precedence
1. **Candidate Pool Partitioning**: On each scheduler tick, the coordinator queries up to `batchSize * candidatePoolMultiplier` eligible `QUEUED` tasks from PostgreSQL, sorted primarily by effective priority (base priority + waiting age) and secondarily by submission timestamp.
2. **Tenant Grouping**: Tasks are grouped into per-tenant FIFO queues while preserving internal priority ordering.
3. **Interleaved Round-Robin Selection**: The scheduler iterates in round-robin fashion across all active tenant queues, taking 1 task per tenant per turn until the candidate batch is filled or all tenant queues are exhausted.
4. **Precedence Invariant**:
   - `HIGH` or `CRITICAL` priority tasks receive higher selection probability because candidate retrieval factors in priority-aging.
   - However, within the candidate pool, **no single tenant can consume more than 1 task per round-robin pass**, ensuring that small tenants (e.g. Tenant B with 10 tasks) receive guaranteed scheduling slots alongside Tenant A.

---

## 5. Token Bucket Rate Limiting (`TenantRateLimiter`)

Distributed rate limiting ensures that a tenant cannot overwhelm the cluster dispatch rate.

### Token Bucket Algorithm
- **Formula**:
  $$\text{refill} = \min(\text{burstCapacity}, \text{currentTokens} + \Delta t \times \text{tasksPerSecond})$$
  $$\text{if } \text{refill} \ge 1.0 \implies \text{admit, } \text{tokens} = \text{refill} - 1.0$$
- **Atomic Lua Script**: When Redis is active, token evaluation and consumption execute in a single atomic Lua script (`EVALSHA` / `EVAL`), eliminating check-then-set race conditions between concurrent coordinator replicas.
- **Bounded State & TTL**: Redis keys (`chronos:tenant:{tenantId}:rate`) store `{tokens, last_refreshed_epoch_ms}` and carry an explicit TTL of:
  $$\text{TTL} = \max\left(60\text{s}, 2 \times \frac{\text{burstCapacity}}{\text{tasksPerSecond}}\right)$$
  Idle tenant keys automatically expire, preventing Redis memory leakage.
- **Fail-Open Policy for Rate Limiting**: If Redis is unreachable during rate limit checks, `RedisTenantRateLimiter` logs a warning and fails open (`return true`). The system prioritizes task dispatch over strict rate limiting during transient Redis degradation. Worker saturation is independently prevented by worker-capacity backpressure.

---

## 6. Atomic Tenant Concurrency Management (`TenantConcurrencyManager`)

Per-tenant concurrency limits (`maxConcurrentTasks`) guarantee that a tenant does not exceed their allocated cluster execution capacity.

### Concurrency Guarantees
1. **Atomic Check-and-Add via Lua**:
   When Redis is available, concurrency is tracked in a Redis Set (`chronos:tenant:{tenantId}:running`). A custom Lua script executes atomically:
   ```lua
   local key = KEYS[1]
   local max_concurrent = tonumber(ARGV[1])
   local task_id = ARGV[2]
   if redis.call('SCARD', key) < max_concurrent then
       redis.call('SADD', key, task_id)
       return 1
   else
       return 0
   end
   ```
   No two coordinators can concurrently read capacity and over-allocate slots.
2. **Counter Leak Prevention & Safe Release**:
   Concurrency slots are idempotently released on all task termination and lifecycle transition paths:
   - Task completion (`SUCCEEDED`)
   - Task attempt failure (`FAILED` or retry-queued)
   - Task execution timeout (`TaskTimeoutService`)
   - Worker heartbeat loss & lease reclamation (`ExecutionLeaseService`, `TaskRecoveryService`)
   - Task cooperative cancellation (`cancelTask`)
3. **Failover Reconciliation**:
   If a coordinator crashes or Redis restarts, running sets could become desynchronized. `TenantConcurrencyManager.reconcileWithDurableState(tenantId)` inspects PostgreSQL's authoritative `chronos_tasks` table for rows in `DISPATCHED` and `RUNNING` status for that tenant, resetting the Redis tracking set to the exact durable reality.
4. **Fail-Closed Policy for Concurrency**:
   If Redis is unreachable, `TenantConcurrencyManager` falls back to querying PostgreSQL directly (`countRunningByTenant(tenantId)`). Concurrency is never allowed to fail open, protecting against cluster saturation and resource starvation.

---

## 7. Per-Task Concurrency Keys (`concurrencyKey`)

Chronos Core supports per-task logical concurrency keys (`concurrencyKey` on `Task` and `TaskSubmissionDTO`).
- **Use Case**: Mutually exclusive execution for tasks affecting the same logical resource (e.g. `concurrencyKey = "customer:10492"`, `concurrencyKey = "order:9921"`).
- **Semantics**:
  - Tasks with **different** concurrency keys execute concurrently across the cluster.
  - Tasks with the **same** concurrency key are serialized: while one is `DISPATCHED` or `RUNNING`, others remain `QUEUED`.
- **Implementation**:
  - At scheduling time, `TaskAdmissionController` verifies whether an active task with the same `(tenantId, concurrencyKey)` already exists in PostgreSQL (`countActiveByConcurrencyKey`).
  - At dispatch time, the coordinator acquires a distributed lock in Redis (`chronos:lock:concurrency:{tenantId}:{concurrencyKey}`) using Phase 04 distributed coordination.
  - Upon task completion, failure, timeout, or cancellation, the lock is safely released.

---

## 8. Workflow Concurrency Limits (`maxWorkflowExecutions`)

Workflows support cluster and tenant-level concurrency gating via `maxWorkflowExecutions`.
- **Semantics**:
  - If an incoming workflow submission exceeds `maxWorkflowExecutions` for active executions (`RUNNING`), the workflow execution is created in `PENDING` status.
  - As running workflow executions reach a terminal status (`SUCCEEDED`, `FAILED`, or `CANCELLED`), `triggerPendingExecutions(tenantId)` activates queued `PENDING` workflows in FIFO submission order.
  - Prevents mass workflow fan-outs from exhausting database connection pools and cluster workers.

---

## 9. Hardened Cancellation & Race Handling

Cooperative cancellation hardened across all valid non-terminal stages:
- **`CREATED`**: Cancelled before enqueueing.
- **`QUEUED`**: Cancelled while awaiting scheduling.
- **`DISPATCHED`**: Cancelled in flight; worker notified via gRPC cooperative cancellation.
- **`RUNNING`**: Worker execution interrupted via virtual-thread cooperative cancellation token.
- **Retry-Waiting (`QUEUED` with future `scheduledAt`)**: Cancelled before the backoff timer fires; prevents scheduled retries from executing.

### Race Resolution
1. **Cancellation vs. Worker Start**:
   - If `cancelTask` commits first, `startExecutionAttempt` detects `status == CANCELLED` and rejects the attempt with `InvalidTaskTransitionException`. The worker aborts execution.
2. **Cancellation vs. Task Completion**:
   - PostgreSQL row versioning (`WHERE task_id = ? AND version = ?`) serializes cancellation and completion. If completion commits first, `cancelTask` detects `status == SUCCEEDED` and rejects the cancellation.
3. **Downstream Workflow Isolation**:
   - When a workflow is cancelled, all `PENDING` and `READY` nodes are marked `CANCELLED`. If a late completion arrives for an already finished node, downstream dependency activation checks the parent execution status, ensuring no new tasks are dispatched.

---

## 10. DLQ Management Hardening (`DlqService`)

Phase 09 expands DLQ operations into a structured operational service:
1. **Task Inspection (`inspectTask(taskId)`)**:
   Returns `DlqTaskInspection` containing the task definition, its full execution attempt history (`ExecutionAttempt`), and complete immutable audit trail (`TaskAuditRecord`).
2. **Safe Replay (`replayTask(taskId, reason)`)**:
   - Validates that current status is `DLQ`.
   - Transitions state `DLQ -> QUEUED` using `TaskStateMachine`.
   - Resets `currentAttempt = 0` and sets `scheduledAt = Instant.now()`.
   - Cleans up lingering execution leases.
   - Stages `TaskReplayed` audit record and outbox event.
   - If the task is part of a workflow execution DAG, calls `WorkflowExecutionService.onTaskReplayed(taskId)` to unblock downstream nodes and restore the workflow execution from `FAILED` to `RUNNING`.
3. **Auditable Archival Marker (`archiveTask(taskId, reason)`)**:
   - Preserves complete execution history without deleting rows.
   - Releases execution lease.
   - Appends an audit record with reason `ARCHIVED: <reason>` and publishes `TaskArchived` outbox event.

---

## 11. Interview-Level Correctness: The 12 Critical Architectural Questions

### 1. How do you prevent one tenant from monopolizing the cluster?
> Through a two-stage defense:
> 1. **At Admission**: Tenant concurrency limits (`maxConcurrentTasks`) cap total simultaneous running tasks, and rate limits (`tasksPerSecond`) cap submission throughput.
> 2. **At Scheduling**: The scheduler partitions eligible queued tasks by tenant and applies **interleaved round-robin fair queueing**. Even if Tenant A has 10,000 tasks queued, it is only granted 1 task dispatch per round-robin pass, allowing smaller tenants (e.g. Tenant B with 10 tasks) immediate scheduling opportunities.

### 2. How do you enforce per-tenant concurrency?
> Concurrency is enforced atomically using a Redis Set (`chronos:tenant:{tenantId}:running`) governed by an atomic Lua script that executes `SCARD < limit` and `SADD` within a single Redis operation. If Redis is unavailable, the system fails closed to PostgreSQL queries (`countRunningByTenant`). Slots are released idempotently upon task completion, failure, timeout, or cancellation.

### 3. How does distributed rate limiting work?
> It uses an atomic token bucket algorithm in Redis (`tasksPerSecond` fill rate, `burstCapacity` ceiling). On each admission check, a Lua script evaluates elapsed time since last refresh, calculates token refill, consumes a token if available, and updates the timestamp. Keys have an automatic TTL ($2 \times \text{burst} / \text{rate}$) to prevent Redis memory leaks.

### 4. What happens when Redis is unavailable?
> Policies are explicitly bifurcated based on correctness guarantees:
> - **Rate Limiting**: **Fails open**. Task dispatch continues; cluster safety is maintained by worker-capacity backpressure and database concurrency checks.
> - **Concurrency Enforcement**: **Fails closed**. Coordinator queries PostgreSQL directly (`countRunningByTenant`) rather than allowing unsafe cluster over-allocation.
> - **Distributed Locks**: **Fails closed**. Mutually exclusive tasks wait until locks can be safely acquired.

### 5. What happens when PostgreSQL is unavailable?
> **Admission and state transitions fail safely and synchronously**. Chronos Core never uses an in-memory shadow queue to buffer writes during database downtime, as doing so violates durable at-least-once delivery guarantees. Submissions throw database exceptions immediately to callers.

### 6. How do you prevent duplicate task submission?
> By enforcing a unique database constraint on `(tenant_id, idempotency_key)` in PostgreSQL (`uq_chronos_tasks_tenant_idempotency`). When concurrent threads or coordinators submit identical tasks with the same idempotency key, PostgreSQL throws a unique constraint violation. The transactional service intercepts this and returns the existing task record, guaranteeing that duplicate execution attempts cannot be spawned.

### 7. How does cancellation race with task completion?
> State transitions are serialized through PostgreSQL row versioning (`WHERE task_id = ? AND version = ?`).
> - If cancellation commits first, `startExecutionAttempt` or `completeExecutionAttempt` detects version conflict or terminal status (`CANCELLED`) and rejects the transition with `InvalidTaskTransitionException`.
> - If completion commits first, `cancelTask` detects `SUCCEEDED` status and rejects cancellation. State transitions remain strictly monotonic.

### 8. How does DLQ replay interact with workflows?
> Replaying a dead-lettered workflow task via `DlqService.replayTask`:
> 1. Resets the task to `QUEUED` with fresh attempts.
> 2. Re-activates the corresponding `WorkflowExecutionNode` from `FAILED` to `READY`.
> 3. Restores the parent `WorkflowExecution` from `FAILED` to `RUNNING`.
> 4. Does **not** create a duplicate workflow node or duplicate workflow execution; existing node and execution identities are preserved.

### 9. How do you prevent concurrency-counter leaks?
> By ensuring that every termination path in the coordinator releases the tenant's concurrency slot:
> - Normal completion (`TransactionalTaskService.completeExecutionAttempt`)
> - Heartbeat expiration and lease reclamation (`TaskRecoveryService`)
> - Execution timeout (`TaskTimeoutService`)
> - Cooperative cancellation (`TransactionalTaskService.cancelTask`)
> Furthermore, background recovery sweeps periodically reconcile Redis tracking sets against PostgreSQL's authoritative running tasks (`reconcileWithDurableState`).

### 10. How does coordinator failover affect tenant limits?
> Concurrency and quota state is decoupled from coordinator in-memory state:
> - Authoritative task state is durable in PostgreSQL (`status IN ('DISPATCHED', 'RUNNING')`).
> - Distributed counters reside in Redis with TTLs or are backed by atomic database queries.
> - When a new coordinator acquires the leader lease, it executes recovery sweeps and reconciles Redis tenant sets against PostgreSQL rows, preventing stale limits after failover.

### 11. How do priority and fairness interact?
> Candidate pool selection balances both concerns:
> 1. Tasks are pulled into the candidate batch using **priority-aging score** ($\text{priorityScore} + \text{waitMinutes} \times \text{multiplier}$), ensuring high-priority and starving tasks are prioritized.
> 2. Within the candidate batch, **interleaved round-robin fair queueing** selects tasks across tenants, ensuring that low-priority tasks from smaller tenants are not completely blocked by massive backlogs from high-priority tenants.

### 12. How do you prevent starvation?
> Through a dual anti-starvation mechanism:
> 1. **Priority Aging**: Every minute a task remains queued, its effective priority score increments, eventually elevating low-priority tasks above newer high-priority tasks.
> 2. **Tenant Fairness**: Round-robin candidate interleaving ensures every tenant with pending work receives dispatch opportunities on every scheduler tick regardless of other tenants' backlog sizes.
