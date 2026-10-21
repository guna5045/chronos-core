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

## 2. Distributed System Failure Modes (Planned - Future Phases)

The following distributed failure modes represent the planned operational resiliency architecture:

| Distributed Scenario | Implementation Phase | Planned Mitigation Mechanism |
| :--- | :--- | :--- |
| **Active Coordinator Crash** | Phase 04 | Redis lease TTL expiration, standby promotion, monotonically incremented epoch fencing tokens. |
| **Network Partition (Split Brain)** | Phase 04 / 05 | Fencing token validation on all persistent writes. Outdated leader writes rejected. |
| **Worker Node Crash / Disconnection** | Phase 06 | Missing gRPC heartbeats (threshold 3 beats). Worker marked `UNREACHABLE`; in-flight tasks re-queued. |
| **Transient Database Outage** | Phase 03 | HikariCP reconnect backpressure; incoming requests rejected with 503 until connection pool restored. |
| **Redis Coordination Outage** | Phase 04 | Coordinators pause active dispatching to prevent dual leaders during coordination store loss. |
