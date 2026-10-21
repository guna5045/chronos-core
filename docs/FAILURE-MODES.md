# Chronos Core - Failure Modes & Resiliency Analysis

## Status
- **Current Phase**: Phase 05 (Worker Cluster & gRPC Communication)

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

## 5. Distributed System Failure Modes (Planned - Future Phases)

The following distributed failure modes represent the planned operational resiliency architecture:

| Distributed Scenario | Implementation Phase | Planned Mitigation Mechanism |
| :--- | :--- | :--- |
| **Orphaned In-Flight Task Recovery** | Phase 06 | Coordinator detects evicted worker had active tasks. State machine marks attempts `TIMED_OUT` and re-queues tasks. |
| **Transient Database Outage** | Phase 07 | HikariCP reconnect backpressure; incoming requests rejected with 503 until connection pool restored. |
| **Worker Resource Starvation** | Phase 07 | Worker reports CPU/memory load in heartbeats; coordinator applies backpressure and throttles dispatches. |


