# Chronos Core - Distributed Scheduling & Task Distribution Specification

## Status
- **Current Phase**: Phase 06 (Distributed Scheduling & Task Distribution)
- **Document Version**: 0.6.0

---

## 1. Architectural Foundation & Overview

Chronos Core features a leader-aware, capacity-governed distributed task scheduler. It bridges PostgreSQL durable state, Redis distributed coordination, the in-memory worker cluster registry, and gRPC task execution.

```text
                           TASK SUBMITTED
                                 │
                                 ▼
                          PostgreSQL
                       (Durable State)
                                 │
                                 ▼
                     Leader-Aware Task Scheduler
                 (Only Leader Coordinator Active)
                                 │
                 ┌───────────────┴───────────────┐
                 │                               │
                 ▼                               ▼
       Ready Queue Selection             Scheduling Rules
      (scheduled_at <= now())            (Priority & Aging)
                 │
                 ▼
          Worker Selection
                 │
                 ▼
       Consistent Hash Ring
     (128 Virtual Nodes / Worker)
                 │
                 ▼
       Capacity Gating & Fallback
                 │
                 ▼
       Distributed Task Lock
      (Redis Ownership Leases)
                 │
                 ▼
        gRPC Worker Dispatch
                 │
                 ▼
       Task State Transition
     (QUEUED ──► RUNNING in DB)
```

> **Precedence Rule & Architectural Accuracy**:  
> - **Execution Semantics**: At-least-once delivery with idempotency, distributed task locking, and fencing-based stale-operation protection. We do **not** claim "exactly-once execution".  
> - **Consistent Hashing**: Provides deterministic ownership, stable worker mapping, and minimal remapping upon membership change. It is **not** a load balancer and does not guarantee perfectly equal distribution. Dynamic worker saturation is handled by capacity gating and admission control.  
> - **PostgreSQL as Single Source of Truth**: The scheduler never moves durable task state into Redis or memory. If dispatch fails or workers are saturated, tasks remain safely in `QUEUED` status in PostgreSQL.

---

## 2. Leader-Only Scheduling & Lifecycle

### 2.1 Leadership Guard
Only the active leader coordinator executes the scheduling loop:
1. Follower coordinators maintain their scheduler in a suspended standby state (`isRunning() == false`).
2. When a coordinator acquires leadership via `LeaderElectionService`:
   - It receives a monotonically increasing leader epoch.
   - It transitions the scheduler to active and starts the polling loop.
3. If leadership is lost (e.g. lease TTL expiration, renewal failure, or graceful release):
   - The scheduler receives an immediate `onLeadershipLost(LeaderIdentity)` notification.
   - The background polling loop pauses immediately, preventing zombie operations.

### 2.2 Generational Fencing Protection
Every scheduling cycle queries `FencingTokenService.getCurrentFencingToken()` to assert that the current leader epoch remains valid. If another coordinator has claimed leadership and incremented the cluster epoch, any in-flight batch is halted immediately with a `StaleLeaderException`.

---

## 3. PostgreSQL Task Selection

The scheduler queries PostgreSQL for candidate tasks ready for admission:

```sql
SELECT task_id, task_type, payload, priority, status, tenant_id,
       idempotency_key, created_at, scheduled_at, deadline, timeout_ms,
       max_attempts, current_attempt, version
FROM chronos_tasks
WHERE status = 'QUEUED'
  AND (scheduled_at IS NULL OR scheduled_at <= :asOf)
ORDER BY
  CASE priority
    WHEN 'CRITICAL' THEN 1
    WHEN 'HIGH' THEN 2
    WHEN 'NORMAL' THEN 3
    WHEN 'LOW' THEN 4
    ELSE 5 END ASC,
  scheduled_at ASC NULLS FIRST,
  created_at ASC
LIMIT :limit;
```

- **Scheduled-At Enforcement**: Tasks with `scheduled_at > now()` are strictly excluded from selection. Future tasks remain dormant in `QUEUED` until their scheduled execution window arrives.
- **Batch Processing**: Configured via `chronos.scheduler.batch-size` (default: 50) to prevent loading large queues into memory.

---

## 4. Priority Scheduling & Anti-Starvation Guarantees

Tasks are prioritized according to their declared `TaskPriority`:
`CRITICAL` &gt; `HIGH` &gt; `NORMAL` &gt; `LOW`.

### 4.1 Starvation Aging Prevention
In distributed systems, a continuous influx of high-priority tasks risks permanently starving lower-priority work. Chronos Core resolves this via **Aging-Based Fairness**:
1. Tasks that have remained queued longer than `chronos.scheduler.aging-threshold` (default: 30s) are designated as **starved**.
2. Each scheduling batch reserves an anti-starvation quota (default: 20% of `batch-size`) specifically for starved tasks:
   ```sql
   SELECT ... FROM chronos_tasks
   WHERE status = 'QUEUED'
     AND (scheduled_at IS NULL OR scheduled_at <= :asOf)
     AND created_at <= :cutoff
   ORDER BY created_at ASC
   LIMIT :maxStarved;
   ```
3. Starved tasks are placed at the front of the candidate batch ahead of newer high-priority tasks, ensuring guaranteed forward progress for low-priority workloads.

---

## 5. Deadline Awareness

Before dispatching any candidate task, the scheduler verifies its `deadline`:
- If `task.deadline != null && task.deadline < now()`:
  - The task is **not** dispatched to a worker.
  - The scheduler acquires the task lock and executes an atomic state transition:
    `QUEUED ──► CANCELLED`
  - Reason logged: `"Task deadline expired before dispatch"`.
  - The cancellation is recorded in PostgreSQL with an audit log and outbox event.

---

## 6. Consistent Hash Ring & Virtual Nodes

### 6.1 Ring Architecture
Chronos Core implements a deterministic consistent hash ring backed by `ConcurrentSkipListMap<Long, String>`:
- **128 Virtual Nodes Per Worker**: Each physical worker node is replicated 128 times on the ring (`worker-id#0` to `worker-id#127`).
- **64-Bit Integer Space**: Virtual nodes and task IDs are hashed uniformly across the full `[Long.MIN_VALUE, Long.MAX_VALUE]` integer range.
- **Deterministic Lookup**: Given `hash(taskId)`, the scheduler locates the first virtual node at or after the hash (`ceilingEntry`), wrapping around to `firstEntry` if the end of the ring is reached.

### 6.2 Hash Function Specification
- **Algorithm**: MD5 (128-bit digest, truncated to 64-bit signed integer).
- **Non-Cryptographic Notice**: MD5 is used strictly as a fast, uniform, deterministic distribution hash function to map keys uniformly across the ring. **MD5 is NOT used for security, encryption, or cryptographic authentication.**

### 6.3 Measured Distribution & Minimal Remapping
Benchmark evaluation with 5 workers, 128 virtual nodes (640 total virtual nodes), and 10,000 sampled tasks demonstrated:
- **Distribution Balance**: Individual worker allocations ranged between 17.87% and 22.43% (theoretical ideal: 20.0%).
- **Worker Addition Remapping**: Adding a 6th worker remapped only 14.63% of existing keys (theoretical ideal: 16.67%).
- **Worker Removal Remapping**: Removing the added worker resulted in 0% unintended remapping &mdash; all keys returned exactly to their original mapped worker.

---

## 7. Worker Selection & Capacity Gating

Worker selection combines consistent hashing with real-time cluster health and capacity interrogation:
1. **Liveness Gate**: The hash ring is reconciled against active workers from `WorkerRegistry`. Only workers in the `ACTIVE` state are eligible. Workers in `SUSPECTED` or `REMOVED` states are strictly excluded.
2. **Capacity Gate**: The candidate worker's capacity is evaluated:
   `availableCapacity = maxConcurrency - activeTasks > 0`.
3. **Topological Ring Fallback**: If the primary worker assigned by consistent hashing is saturated, the selector traverses candidate virtual nodes along the ring, evaluating subsequent active workers.
4. **Admission Backpressure**: If all candidate active workers are saturated, the selector returns empty. The task remains safely in `QUEUED` status in PostgreSQL, and the scheduler delays re-polling until the next interval, avoiding busy-loops.

---

## 8. Distributed Locking & Transaction Boundaries

To prevent competing coordinators or concurrent iterations from double-scheduling tasks, dispatch enforces strict locking boundaries:

```text
       Scheduler Iteration
               │
               ▼
   distributedLockService.tryAcquire("chronos:task:lock:" + taskId, 30s)
               │
        ┌──────┴──────┐
     Acquired      Failed (Owned by other coordinator)
        │             │
        │             ▼
        │          Skip task
        ▼
   fencingTokenService.assertActiveLeader(epoch)
        │
        ▼
   Re-read Task from PostgreSQL (status == QUEUED?)
        │
        ▼
   workerSelector.selectWorker(task)
        │
        ▼
   workerDispatchClient.dispatchTask(worker, assignment, 5s)
        │
   ┌────┴────┐
Accepted  Rejected / Unavailable
   │         │
   │         ▼
   │      Leave task QUEUED in DB
   ▼
transactionalTaskService.startExecutionAttempt(...)
(PostgreSQL commit: QUEUED ──► RUNNING + Attempt + Audit + Outbox)
   │
   ▼
distributedLockService.release(lockHandle)  (Always in finally)
```

### Safe Boundary Principles
1. **Short Lock Holding**: The Redis lock is held only during the brief dispatch handshake, not for the entire multi-minute task execution.
2. **PostgreSQL as Single Truth**: Redis lock acquisition and PostgreSQL commit are decoupled. If Redis lock release fails, the 30-second TTL expires automatically without corrupting durable task state.
3. **Optimistic Version Checks**: In the unlikely event that a delayed coordinator attempts a state transition, PostgreSQL row-level version checks reject the stale write.

---

## 9. Configuration Reference

### Scheduler Properties (`chronos.scheduler`)
```yaml
chronos:
  scheduler:
    enabled: true
    poll-interval-ms: 100
    batch-size: 50
    dispatch-timeout: 5s
    task-lock-ttl: 30s
    aging-threshold: 30s
    starvation-batch-ratio: 0.2
```

### Distribution Properties (`chronos.distribution`)
```yaml
chronos:
  distribution:
    virtual-nodes-per-worker: 128
```
