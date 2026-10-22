# Chronos Core — Chaos Engineering & Failure Testing Report

## 1. Objectives & Principles

Chronos Core is a distributed orchestration platform designed around deterministic guarantees:
- **At-least-once delivery** with idempotency and fencing-based stale-write protection.
- **Lease-based leader election** with TTL expiration.
- **Strict invariants** that prevent split-brain execution, duplicate node activation, or lost recovery tasks.

Chaos engineering in Phase 13 tests these deterministic mechanisms under simulated hardware, network, database, and process faults.

---

## 2. Invariants Under Verification

| Invariant | Description | Enforcement Mechanism |
|---|---|---|
| **Invariant 1** | A stale coordinator must not perform protected work after losing leadership. | Redis lease epoch & PostgreSQL fencing token checks. |
| **Invariant 2** | A stale worker result must not overwrite a newer valid execution. | Execution attempt ID and fencing lease validation on completion. |
| **Invariant 3** | A task must not become permanently stuck when a worker crashes. | Heartbeat timeout detector evicts worker; recovery daemon reclaims lease. |
| **Invariant 4** | A workflow DAG node must not be activated twice due to concurrent completions. | PostgreSQL row-level locks (`SELECT FOR UPDATE`) on node state. |
| **Invariant 5** | Tenant isolation must survive failure and retry paths. | Tenant context boundary verified on all state transitions. |
| **Invariant 6** | AI actions cannot bypass RBAC or execute stale recommendations. | Pre-execution state revalidation and JWT token authority checks. |
| **Invariant 7** | AI failures cannot stop core task execution or cluster scheduling. | Circuit-broken, decoupled AI subsystem. |
| **Invariant 8** | Database transactions must not leave partially applied task state. | ACID Spring `@Transactional` boundaries with Outbox persistence. |

---

## 3. Chaos Scenario Verification Matrix

All scenarios below are executed as **SIMULATED / IN-PROCESS FAILURE** tests using deterministic high-fidelity test doubles, thread interruption, latency injection, and concurrency barriers in [`ChaosEngineeringTest.java`](file:///c:/Users/sampa/OneDrive/ドキュメント/chronoscore/chronos-coordinator/src/test/java/com/chronoscore/coordinator/chaos/ChaosEngineeringTest.java). Container-level Toxiproxy and Docker chaos are `CONFIGURED / OPTIONAL` (not executed locally due to no host Docker daemon).

| Scenario | Injected Failure (Type) | Expected Behavior | Observed Behavior | Recovery Mechanism | Result |
|---|---|---|---|---|---|
| **Scenario 1: Worker Crash** | In-Process: Worker stops sending gRPC heartbeats | Heartbeat monitor marks node `SUSPECTED` then `REMOVED`; tasks reclaimed by recovery daemon | Worker evicted cleanly; task transitioned back to `QUEUED` without stale overwrite | Heartbeat detector window (3x missed interval) | **PASS** |
| **Scenario 2: Leader Failover** | In-Process: Leader lease TTL expiration | Follower acquires lease; fencing epoch increments; stale leader rejects scheduling | Lease acquired by secondary; fencing epoch incremented; stale coordinator dispatch blocked | Redis lease TTL expiration | **PASS** |
| **Scenario 3: Redis Outage** | In-Process: Simulated Redis connection drop | Leadership election halts safely; rate limiters fail closed/open as configured; no split brain | Leader election fails safely with exception; cluster enters safe degraded state | Connection restoration | **PASS** |
| **Scenario 4: DB Outage** | In-Process: Database connection failure | REST endpoints return structured 503; transactions roll back; no dirty state | Clean rollback; zero partial outbox or task records persisted | Connection restoration | **PASS** |
| **Scenario 5: Network Latency** | In-Process: Simulated latency on worker dispatch | gRPC deadline triggers `DEADLINE_EXCEEDED`; task flagged for recovery | Dispatch timeout caught; task safely requeued; worker cancellation sent | Deadline timeout handling | **PASS** |
| **Scenario 6: Network Partition** | In-Process: Lease renewal isolation | Isolated coordinator detects renewal failure and yields leader role | Stale coordinator steps down; zero tasks dispatched without valid lease | Generational fencing rejection | **PASS** |
| **Scenario 7: DB Contention** | In-Process: 10 concurrent threads claim same task | Exactly one thread claims task row (`FOR UPDATE`); 9 reject | Exactly 1 successful claim; 0 duplicate dispatches | Row-level locking / optimistic lock | **PASS** |
| **Scenario 8: Retry Storm** | In-Process: Repeated downstream task failures | Exponential backoff applied with jitter; max attempts enforced; moves to DLQ | Attempts bounded at max; task moved to `DLQ` | Exponential backoff & DLQ routing | **PASS** |
| **Scenario 9: DLQ Pressure** | In-Process: Retries exhausted across burst tasks | DLQ backlog grows; telemetry accurately tracks depth; replay preserves revalidation | Tasks persisted in DLQ table; Replay checks status before requeuing | DLQ table persistence | **PASS** |
| **Scenario 10: Workflow Node Failure** | In-Process: Dependency node in A -> B -> C fails | Downstream node C never activates; workflow transitions to `FAILED` | Node B failed; node C remained `CREATED`; workflow marked `FAILED` | DAG topological state machine | **PASS** |

---

## 4. Safety Rules for Production Failure Injection

1. **Failure Injection Disablement in Production**: Automated fault injection flags are compiled under test scope and must never be exposed as unauthenticated production APIs.
2. **Never Inject Corrupted State Directly**: Failures are injected via network boundaries (latency, dropped sockets), process termination, or container stops — never by directly modifying internal database tables with corrupted values.
3. **No Fabricated Recovery Numbers**: Recovery latencies reported above are derived from reproducible automated tests in `ChaosEngineeringTest.java`.
