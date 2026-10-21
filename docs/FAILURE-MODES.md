# Chronos Core - Failure Modes & Resiliency Analysis

## Status
- **Current Phase**: Phase 02 (Core Domain Model & Shared Contracts)

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

## 3. Distributed System Failure Modes (Planned - Future Phases)

The following distributed failure modes represent the planned operational resiliency architecture:

| Distributed Scenario | Implementation Phase | Planned Mitigation Mechanism |
| :--- | :--- | :--- |
| **Active Coordinator Crash** | Phase 04 | Redis lease TTL expiration, standby promotion, monotonically incremented epoch fencing tokens. |
| **Network Partition (Split Brain)** | Phase 04 / 05 | Fencing token validation on all persistent writes. Outdated leader writes rejected. |
| **Worker Node Crash / Disconnection** | Phase 06 | Missing gRPC heartbeats (threshold 3 beats). Worker marked `UNREACHABLE`; in-flight tasks re-queued. |
| **Transient Database Outage** | Phase 03 | HikariCP reconnect backpressure; incoming requests rejected with 503 until connection pool restored. |
| **Redis Coordination Outage** | Phase 04 | Coordinators pause active dispatching to prevent dual leaders during coordination store loss. |
