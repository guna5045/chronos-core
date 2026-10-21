# Chronos Core - Failure Modes & Resiliency Analysis

## Status
- **Current Phase**: Phase 08 (Workflow & DAG Orchestration)

---

## 1. Domain-Level Failure Modes (Active in Phase 02)

| Failure Scenario | Domain Impact | Detection & Mitigation | Resulting State |
| :--- | :--- | :--- | :--- |
| **Invalid State Transition** | Attempted illegal lifecycle progression (e.g. `SUCCEEDED -> RUNNING`, `RUNNING -> CREATED`). | `TaskStateMachine` validates all transitions deterministically. Throws `InvalidTaskTransitionException`. | Operation rejected; existing state preserved untouched. |
| **Retry Exhaustion** | Task attempt fails and `currentAttempt >= maxAttempts`. | State machine evaluates `transitionOnFailure(taskId, currentAttempt, maxAttempts)`. Detects attempt threshold reached. | Transitions to `DLQ` (Dead-Letter Queue). |
| **Task Cancellation** | Operator abort or task deadline exceeded during queueing, dispatch, or execution. | Transition to `CANCELLED` permitted from `CREATED`, `QUEUED`, `DISPATCHED`, and `RUNNING`. | Transitions to `CANCELLED` (terminal state). |
| **Stale Version / Update Conflict** | Concurrent coordinator or worker attempts update with outdated version counter. | Optimistic concurrency check: `task.getVersion()` must match expected database row version. | Update rejected via version conflict; avoids lost updates. |
| **Invalid Task Submission** | Submission payload missing required fields (blank type, negative timeout, inconsistent deadline). | `TaskValidator.validate(task)` enforces domain invariants before enqueueing. | Throws `InvalidTaskException`; task rejected at boundary. |
| **Duplicate Logical Submission** | Client retries submission with identical `idempotencyKey` for the same tenant. | Domain model captures `idempotencyKey`. Future persistence tier enforces unique constraint `(tenantId, idempotencyKey)`. | Deduplicated to existing task; duplicate execution prevented. |

---

## 2. Persistence & Database Failure Modes (Active in Phase 03)

| Failure Scenario | Persistence Impact | Detection & Mitigation | Resulting State |
| :--- | :--- | :--- | :--- |
| **Concurrent Version Stale Write** | Two workers/transactions attempt to update the same task concurrently from the same version. | Repository executes `UPDATE ... WHERE task_id = ? AND version = ?`. Second update updates 0 rows and throws `OptimisticLockingException`. | First update succeeds; second update fails and caller must re-read newer state. Lost updates prevented. |
| **Mid-Transaction Failure** | Unhandled exception or storage error occurs after task mutation but before transaction commit (e.g., audit log error or outbox write failure). | Spring `@Transactional` transaction boundary detects error and triggers full PostgreSQL `ROLLBACK`. | Complete rollback: task update, audit log, and outbox event are discarded together. No partial state commits. |
| **Duplicate Idempotency Key** | Client retries submission with the same `(tenant_id, idempotency_key)`. | PostgreSQL enforces partial unique index `uq_chronos_tasks_tenant_idempotency`. Unique violation is caught and translated to `DuplicateTaskException`. | Duplicate task rejected; original task preserved. |
| **Database Unavailable / Outage** | PostgreSQL is unreachable or connection pool exhausted. | HikariCP pool fails with connection acquisition timeout; operations fail fast with `PersistenceException`. | Transaction aborted; system refuses state mutations until durable database is reachable. |
| **Database Constraint Violation** | Invalid domain values bypass validation (e.g. `max_attempts <= 0`, negative attempts). | PostgreSQL check constraints (`chk_tasks_max_attempts`, `chk_tasks_current_attempt`, `chk_tasks_version`) reject invalid data. | Query aborted; transaction rolled back. |
| **Flyway Migration Failure** | Schema script syntax error or lock conflict during startup. | Flyway detects migration failure, prevents application startup, and records failed migration status. | Coordinator startup halted until migration issue is remediated. |

---

## 3. Distributed Coordination Failure Modes (Active in Phase 04)

| Failure Scenario | Distributed Impact | Detection & Mitigation | Resulting State |
| :--- | :--- | :--- | :--- |
| **Active Coordinator Crash** | The current leader abruptly dies without graceful shutdown. | Redis leader lock key TTL expires (5s). Follower coordinators attempt `SET chronos:leader:lock <id> NX EX 5`. Winner becomes new leader and increments `chronos:leader:epoch`. | Seamless failover to new leader; split-brain avoided. |
| **Stale Leader Resumption (GC Pause)** | Leader node suffers a long GC pause (>5s). Lease expires; another node becomes leader with higher epoch. Old leader resumes. | Generational fencing tokens. When old leader attempts downstream operations or lease renewals, epoch check (`epoch < currentClusterEpoch`) rejects operations via `StaleLeaderException`. | Old leader detects ownership loss and demotes to `FOLLOWER`. |
| **Lease Renewal Ownership Mismatch** | A slow coordinator attempts to extend its lease after another coordinator claimed leadership. | Atomic Lua script compares `redis.call('get', KEYS[1]) == ARGV[1]` before executing `EXPIRE`. Returns `0`. | Renewal returns `false`; caller transitions to `LEADERSHIP_LOST` and notifies listeners. |
| **Stale Task Lock Release** | Task lock expires in Redis due to delayed execution. Another worker acquires the lock. First worker attempts late release. | Atomic Lua script compares `redis.call('get', KEYS[1]) == ARGV[1]` before executing `DEL`. Returns `0`. | Release returns `false`; newly acquired lock remains completely intact in Redis. |
| **Redis Coordination Store Outage** | Redis becomes unreachable or experiences connection timeouts. | Operations catch Redis connection exceptions, log critical error alerts, transition coordinator to `FOLLOWER`/`LEADERSHIP_LOST`. Coordinators refuse to operate as uncoordinated rogue nodes. | Fail-safe: leader-only operations halted until Redis cluster connectivity is restored. |

---

## 4. Worker Cluster & gRPC Failure Modes (Active in Phase 05)

| Failure Scenario | Cluster Impact | Detection & Mitigation | Resulting State |
| :--- | :--- | :--- | :--- |
| **Transient Heartbeat Miss (Network Glitch)** | Worker fails to deliver heartbeat ping for > 3,000ms. | `WorkerHealthMonitor` detects `Instant.now() - lastHeartbeatAt > 3000ms`. | Worker transitions to `SUSPECTED`. Excluded from new task dispatch. |
| **Worker Recovery after Delay** | Worker in `SUSPECTED` state resumes heartbeat stream. | Coordinator receives valid ping matching registered `sessionId`. `WorkerRegistry` restores worker to `ACTIVE`. | Restored to `ACTIVE` without requiring full re-registration cycle. |
| **Dead Worker Eviction** | Worker abruptly crashes or network permanently severed for > 6,000ms. | `WorkerHealthMonitor` detects elapsed time > 6,000ms. | Worker transitions to `REMOVED` and is evicted from memory. In-flight tasks become candidates for recovery. |
| **Stale Session Reconnect (Zombie Worker)** | Worker reboots or resumes after pause, attempting heartbeats with previous `sessionId`. | Coordinator verifies `sessionId` against active registry snapshot. Mismatch detected. | Heartbeat rejected with `STALE_SESSION`; worker instructed to perform clean re-registration. |
| **Worker Capacity Saturation** | Dispatch sent to worker whose virtual thread executor has reached `maxConcurrency`. | `VirtualThreadTaskExecutor` atomic CAS fails. Worker returns `accepted = false` with `CAPACITY_EXHAUSTED`. | Coordinator aborts assignment and worker remains stable without thread pool exhaustion. |
| **Dispatch / Cancellation RPC Timeout** | Worker node is slow or unresponsive during task dispatch or cancellation call. | `WorkerDispatchClient` enforces a strict 5,000ms gRPC deadline via `withDeadlineAfter(...)`. | Call fails fast with `DEADLINE_EXCEEDED`; coordinator prevents thread hanging. |
| **Coordinator Failover Re-Registration** | Coordinator leader dies; follower takes over. Worker detects broken gRPC stream. | Worker client catches stream termination, triggers exponential reconnect backoff, and performs fresh registration handshake. | Worker acquires new `sessionId` from new coordinator and resumes heartbeat stream seamlessly. |

---
 
## 5. Distributed Scheduling & Distribution Failure Modes (Active in Phase 06)
 
| Failure Scenario | Scheduling Impact | Detection & Mitigation | Resulting State |
| :--- | :--- | :--- | :--- |
| **Stale Leader Scheduling Attempt** | A coordinator node that lost its lease or experienced a GC pause attempts to run a scheduling loop. | Generational fencing assertion: `FencingTokenService.validateFencingToken(...)` checks leader epoch before batch execution. Throws `StaleLeaderException`. | Scheduling aborted immediately; no stale dispatches or DB writes occur. Coordinator reverts to standby. |
| **Concurrent Task Scheduling Contention** | Multiple coordinator scheduling loops or threads attempt to schedule the same task. | Distributed task locking: `DistributedLockService.tryAcquire("task:" + taskId, ttl)` acquires lock before dispatch. Second attempt fails lock acquisition. | Winning thread dispatches task; losing thread logs debug message and skips task cleanly. |
| **Primary Hash Ring Worker Saturated** | The worker designated by consistent hashing has reached `maxConcurrency`. | `ConsistentHashWorkerSelector` detects `!primary.hasAvailableCapacity()`. It walks the hash ring clockwise via `ring.getCandidates(key)` to find the next active worker with capacity. | Task is routed to fallback active worker; execution proceeds without artificial delay. |
| **All Active Workers Saturated (Admission Backpressure)** | Entire worker cluster is running at 100% capacity. | `WorkerSelector.selectWorker(...)` returns `Optional.empty()`. Scheduler logs warning and marks dispatch `NO_WORKER_AVAILABLE`. | Task remains securely persisted in PostgreSQL in `QUEUED` state. No busy-loop or in-memory bloat. Picked up on subsequent poll. |
| **Expired Task Deadline Pre-Dispatch** | A task's deadline passes while queued before worker assignment. | Scheduler checks `Instant.now().isAfter(task.getDeadline())`. | Task is not dispatched to worker. Atomically transitioned to `CANCELLED` via `TransactionalTaskService`. |
| **Worker Dispatch Rejection** | Worker gRPC endpoint returns `accepted = false` (`CAPACITY_EXHAUSTED` or worker-side rejection). | Scheduler checks `response.getAccepted()`. Recognizes rejection. | State transition aborted. Distributed lock released safely. Task remains `QUEUED` in PostgreSQL. |
| **Worker Node Unregistered / Removed** | Worker crashes or is evicted from registry between selection and dispatch. | `WorkerRegistry.getActiveWorkers()` excludes `REMOVED` or `SUSPECTED` nodes. Hash ring reconciles active set. Dispatch client detects dead channel. | Selection skips inactive node; gRPC failure aborts attempt gracefully, preserving `QUEUED` state. |
 
---
 
## 6. Fault Tolerance & Task Recovery Failure Modes (Active in Phase 07)

| Failure Scenario | Recovery Impact | Detection & Mitigation | Resulting State |
| :--- | :--- | :--- | :--- |
| **Worker Crash / Hard Failure** | In-flight tasks left in `DISPATCHED` or `RUNNING` on an unreachable worker. | Heartbeat timeout (>6s) causes worker transition to `REMOVED`. `WorkerHealthMonitor` immediately calls `TaskRecoveryService.recoverWorkerFailure(workerId)`. Active lease identified and attempt marked `WORKER_UNAVAILABLE`. | Evaluates `RetryPolicy`: transitions to `QUEUED` with exponential backoff delay, or `DLQ` if `maxAttempts` exhausted. |
| **Task Execution Lease Expiration** | Worker freezes or network partition prevents lease renewal without heartbeat monitor eviction. | `RecoveryScheduler` calls `TaskRecoveryService.reclaimExpiredLeases()` periodically. Scans `chronos_task_leases` where `expires_at <= now()`. | Expired lease detected; attempt marked `WORKER_UNAVAILABLE`; retry policy applied; task requeued with backoff or moved to `DLQ`. |
| **Network Partition & Stale Completion** | Worker A disconnected/evicted, Attempt 1 reassigned to Worker B (Attempt 2). Worker A completes later and reports success. | Attempt fencing & state validation: `TaskRecoveryService.handleTaskCompletion(...)` checks `attemptId` and `attemptNumber` against `task.getCurrentAttempt()`, and checks terminal task status. Stale result rejected with `STALE_RESULT_REJECTED` audit log. | Attempt 1 rejected; Attempt 2 result preserved; task remains in terminal state committed by legitimate winner. |
| **Task Execution Timeout** | Task runs longer than configured `task.timeout` duration (e.g. infinite loop, external deadlock). | `TaskTimeoutService.scanForTimeouts()` checks `now - attempt.startedAt > task.timeout`. Dispatches cooperative cancellation RPC (`WorkerDispatchClient.cancelTask`) to interrupt virtual thread. | Attempt marked `EXECUTION_TIMEOUT`; retry policy evaluates backoff; task requeued with backoff or routed to `DLQ`. |
| **Timeout vs Completion Race** | Coordinator detects timeout at the exact moment the worker completes execution. | Optimistic concurrency & attempt state check: whichever transaction commits first updates the database row. The losing operation detects updated version or terminal state and is cleanly rejected without corruption. | Durable state consistently reflects whichever operation committed first. |
| **Retry Storm (Cascading Failures)** | Transient downstream outage causes dozens of concurrent tasks to fail simultaneously. | Bounded exponential backoff (`delay = min(maxDelay, baseDelay * 2^(attempt-1))`) with randomized jitter spreads retry scheduling across time, preventing synchronized thundering herd. | Tasks are scheduled with staggered `scheduledAt` timestamps in `QUEUED` state. |
| **Permanent / Non-Retryable Failure** | Task fails with validation, malformed payload, or fatal business error (`INVALID_TASK`, `VALIDATION_ERROR`). | `RetryPolicy.isRetryable(...)` classifies error code. Permanent errors bypass retries. | Task immediately routed to `DLQ` with failure reason recorded in audit log. |
| **Max Retry Attempts Exhaustion** | Task fails repeatedly and reaches `currentAttempt >= maxAttempts`. | `RetryPolicy.shouldRetry(...)` evaluates attempt count against threshold. | Task transitions to `DLQ` (Dead-Letter Queue) with full execution history preserved. |
| **Multi-Coordinator Concurrent Recovery Race** | Multiple coordinators or threads attempt to recover the same orphaned or expired task simultaneously. | PostgreSQL optimistic locking (`WHERE task_id = ? AND version = ?`) ensures only one recovery transaction commits. Losing thread catches `OptimisticLockingException` and aborts safely. | Exactly one attempt update occurs; no duplicate attempts or lost state. |
| **Dead-Letter Queue Replay Race** | Multiple operators or processes invoke DLQ replay on the same failed task. | `DlqService.replayTask(...)` verifies `task.getStatus() == DLQ` within transactional optimistic lock. | First invocation resets task to `QUEUED`; subsequent invocation fails `IllegalStateException` or optimistic lock check. |

---

## 7. Workflow & DAG Orchestration Failure Modes (Active in Phase 08)

| Failure Scenario | Workflow Impact | Detection & Mitigation | Resulting State |
| :--- | :--- | :--- | :--- |
| **Cyclic Dependency in Workflow Definition** | Graph contains a directed cycle (e.g. `A -> B -> C -> A` or self-loop `A -> A`), creating deadlock. | `WorkflowDagValidator.validate(version)` runs DFS 3-coloring. Detects back-edge to node in `GRAY` state. Throws `WorkflowValidationException`. | Workflow rejected prior to publishing; cyclic graph is never persisted as executable. |
| **Orphaned / Missing Dependency Reference** | Workflow edge references a `nodeId` not declared in node definitions. | `WorkflowDagValidator` validates edge node IDs against declared node set. Throws `WorkflowValidationException`. | Workflow rejected at registration boundary. |
| **Concurrent Fan-In Node Activation Race** | Multiple upstream predecessor nodes complete simultaneously, both attempting to activate the common successor node. | Database-level atomic compare-and-swap: `claimNodeForActivation` executes `UPDATE ... SET status = 'READY' WHERE status = 'PENDING'`. Only winning transaction gets 1 row; losing thread sees 0 rows and skips task creation. | Downstream node is activated and submitted to scheduler **exactly once**. Zero duplicate tasks. |
| **Terminal Task Failure / DLQ in Workflow Node** | A workflow node task exhausts all retry attempts and enters `DLQ`, or encounters unrecoverable failure. | `WorkflowExecutionService.onTaskCompleted(taskId, false, ...)` marks node `FAILED`. BFS identifies all reachable downstream descendants and marks them `SKIPPED`. Transitions execution to `FAILED`. | Downstream tasks are never scheduled or executed; workflow halts cleanly in `FAILED` state. |
| **Workflow Cancellation Race with Task Completion** | Operator cancels workflow while an underlying task completes on a worker. | Optimistic concurrency & state check on `WorkflowExecution`: whichever transaction commits first updates state. If workflow is already terminal (`CANCELLED`), task completion is ignored idempotently. If task completes first, cancellation marks remaining nodes. | Consistent final state; no zombie executions or corrupted dependency graphs. |
| **Mutation Attempt on Published Workflow Version** | Operator attempts to add/remove nodes or alter dependencies on an already published version. | `WorkflowService.createDraftVersion` validates immutability. Published versions reject structural mutations. | Operation rejected with `IllegalStateException`; new version must be drafted. |
| **Cross-Execution Dependency Pollution** | Two concurrent executions of the same workflow version run simultaneously. | Complete isolation: each execution maintains its own independent rows in `chronos_workflow_execution_nodes`. Dependency queries always filter strictly by `execution_id`. | Zero state leakage between concurrent executions. |

---

## 8. Distributed System Failure Modes (Planned - Future Phases)

The following distributed failure modes represent the planned operational resiliency architecture for future phases:

| Distributed Scenario | Implementation Phase | Planned Mitigation Mechanism |
| :--- | :--- | :--- |
| **API Gateway Throttling / DDoS** | Phase 09 | Rate-limiting bucket algorithms per tenant at REST ingress gateway. |
| **Dynamic Worker Autoscaling Dropouts** | Phase 12 | Graceful node draining during autoscaler scale-down events via SIGTERM signal traps. |



