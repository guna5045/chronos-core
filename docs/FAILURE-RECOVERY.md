# Chronos Core &mdash; Fault Tolerance, Retries & Task Recovery

## 1. Overview & Delivery Semantics

Chronos Core provides:
> **At-least-once task delivery with idempotency, durable state transitions, task leases, exponential backoff, fencing-based stale execution protection, worker failure detection, and dead-letter handling.**

Chronos Core does **not** claim or implement "exactly-once execution". In any distributed system with network partitions, worker crashes, and variable latencies, a task may be delivered or executed more than once across physical attempts. Chronos Core guarantees safety through:
1. **Idempotent Transitions**: Deterministic state transitions strictly guarded by `TaskStateMachine`.
2. **Optimistic Concurrency Control**: Monotonically incremented task entity versions in PostgreSQL.
3. **Execution Leases**: Active execution attempt ownership tracked durably with expiration timestamps.
4. **Fencing Tokens & Attempt Tracking**: Stale completion reports from disconnected or slow workers are decisively rejected when a newer attempt has commenced or succeeded.
5. **Dead-Letter Queue (DLQ)**: Non-retryable errors or attempts exhausted past `Task.maxAttempts` are safely quarantined without data loss.

---

## 2. Retry Policy & Exponential Backoff

### Failure Classification
Chronos Core explicitly categorizes failures using `FailureClassification`:
- **RETRYABLE**: Transient anomalies eligible for subsequent attempts:
  - `WORKER_UNAVAILABLE`
  - `WORKER_TIMEOUT`
  - `TEMPORARY_NETWORK_FAILURE`
  - `CAPACITY_EXHAUSTED`
  - `EXECUTION_TIMEOUT`
  - `LEASE_EXPIRED`
  - `TRANSIENT_INFRASTRUCTURE_ERROR`
- **NON_RETRYABLE**: Permanent structural errors that immediately escalate to `DLQ`:
  - `INVALID_TASK`
  - `INVALID_PAYLOAD`
  - `UNSUPPORTED_TASK_TYPE`
  - `VALIDATION_ERROR`
  - `PERMISSION_ERROR`

### Bounded Exponential Backoff
For retryable errors, the next attempt delay is computed via:
$$\text{delay} = \min\left(\text{maxDelay}, \text{baseDelay} \times \text{multiplier}^{\text{attempt} - 1}\right)$$
- `baseDelay`: Initial delay (default `1s`).
- `multiplier`: Exponential base (default `2.0`).
- `maxDelay`: Upper bound ceiling (default `60s`).
- **Jitter Strategy**: Full jitter randomizes delays between `[0, delay]` to avoid synchronized retry storms across concurrent workers.

### Scheduled Requeueing
Failed tasks eligible for retry do not immediately flood the cluster. Instead, `task.scheduledAt` is set to `Instant.now().plus(delay)`, and the task transitions back to `QUEUED`. The Phase 06 scheduler naturally picks up the task once the backoff delay has elapsed.

---

## 3. Task Execution Leases

Chronos Core distinguishes between two distinct concurrency controls:
1. **Redis Distributed Task Lock (`chronos:task:lock:{taskId}`)**:
   - Short TTL (default `30s`).
   - Guards *coordinator scheduling and dispatch exclusivity* across competing coordinator nodes.
2. **PostgreSQL Execution Lease (`chronos_task_leases`)**:
   - Durable record in PostgreSQL.
   - Represents *active worker ownership of a specific execution attempt*.
   - Attributes: `taskId`, `attemptId`, `workerId`, `attemptNumber`, `leaseAcquiredAt`, `leaseExpiresAt`, `leaderEpoch`, `version`.

### Lease Expiration
If a worker crashes or becomes partitioned and fails to complete or renew the lease within `leaseExpiresAt`, the lease expires. The `RecoveryScheduler` and `TaskRecoveryService` safely detect the expired lease using `SELECT ... FOR UPDATE SKIP LOCKED` and evaluate the task for recovery.

---

## 4. Stale Execution Protection & Fencing

### The Stale Worker Scenario
Consider a network partition or prolonged GC pause:
1. `Worker A` begins executing Attempt 1.
2. `Worker A` gets partitioned. Coordinator heartbeat detection moves `Worker A` to `SUSPECTED` then `REMOVED`.
3. The coordinator marks Attempt 1 `FAILED` with `WORKER_UNAVAILABLE` and releases the lease.
4. Attempt 2 is scheduled and dispatched to `Worker B`.
5. `Worker B` completes Attempt 2 successfully; the task enters `SUCCEEDED`.
6. `Worker A` reconnects and submits a stale `SUCCEEDED` result for Attempt 1.

### Defense Mechanism
Chronos Core rejects stale results by checking:
- **Terminal Task State**: If the task has already reached `SUCCEEDED`, `FAILED`, `CANCELLED`, or `DLQ`, any late result report is immediately dropped.
- **Monotonic Attempt Check**: If `reportedAttemptNumber < task.getCurrentAttempt()`, the result is rejected as stale.
- **Audit Logging**: The rejected stale attempt is durably recorded in `chronos_task_audit` with reason `STALE_RESULT_REJECTED` and metadata containing the stale worker ID and attempt number.
- **Task State Invariant**: The task remains `SUCCEEDED`.

---

## 5. Task Timeouts & Cooperative Cancellation

Tasks specify an execution timeout duration via `Task.timeout`.
The `TaskTimeoutService`:
1. Periodically queries running tasks.
2. Compares active runtime: $\text{elapsed} = \text{now} - \text{attempt.startedAt}$.
3. If $\text{elapsed} > \text{task.timeout}$:
   - Issues a cooperative `cancelTask` RPC to the worker over gRPC (`ProtoTaskCancelRequest`).
   - Marks the attempt `FAILED` with error code `EXECUTION_TIMEOUT`.
   - Re-evaluates `RetryPolicy`. If attempts remain, the task is requeued with exponential backoff; otherwise moved to `DLQ`.
   - Releases the task execution lease.

---

## 6. Dead-Letter Queue (DLQ) & Service-Layer Replay

A task enters `TaskStatus.DLQ` when:
1. Its attempt counter exhausts `task.maxAttempts`.
2. A non-retryable failure classification is received (e.g., `VALIDATION_ERROR`).

### DLQ Replay Semantics
Operators can replay dead-lettered tasks via `DlqService.replayTask(taskId, reason)`.
Replay guarantees:
- **Validation**: Enforces that the task is currently in `DLQ`.
- **Atomic Transition**: Executes `DLQ -> QUEUED` state transition.
- **Attempt Reset**: Resets `currentAttempt = 0` to grant a fresh execution lifecycle.
- **Immediate Eligibility**: Sets `scheduledAt = Instant.now()`.
- **Lease Release**: Removes any stale leases.
- **Audit Trail & Outbox**: Appends an immutable `TASK_REPLAYED` audit record and publishes a `TaskReplayed` transactional outbox event.

---

## 7. Recovery Concurrency & Leader Coordination

- **Leader-Only Execution**: The `RecoveryScheduler` runs strictly on the active coordinator leader, verified via `LeaderElectionService.isLeader()` and `FencingTokenService.assertActiveLeader(leaderEpoch)`.
- **Concurrency Gate**: Atomic flags prevent overlapping recovery ticks.
- **Row-Level Locking**: `SELECT ... FOR UPDATE SKIP LOCKED` ensures multiple coordinators or recovery threads process disjoint batches without lock contention.
- **Optimistic Locking**: Every task update checks `version = version + 1 WHERE version = :version`, throwing `OptimisticLockingException` upon conflicting writes.
