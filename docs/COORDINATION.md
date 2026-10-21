# Chronos Core - Distributed Coordination Specification

## Status
- **Current Phase**: Phase 04 (Distributed Coordination)
- **Document Version**: 0.4.0

---

## 1. Architectural Foundation & Overview

Chronos Core is engineered for high-availability multi-coordinator cluster deployments. To safely schedule tasks, manage cluster membership, and dispatch execution attempts without split-brain or duplicate execution, coordinator instances require an active coordination layer.

> **Precedence Rule & Architectural Accuracy**:  
> Chronos Core uses **lease-based leader election with fencing tokens**. It is **not** a consensus protocol such as Raft or Paxos. Do not describe Redis lease-based leader election as "consensus-backed" or "Raft/Paxos".  
> Redis acts solely as the ephemeral coordination, lease, and lock store. PostgreSQL remains the authoritative, durable source of truth for task state and execution history.

---

## 2. Redis Key Namespace Architecture

All Redis keys used across the coordination layer are constructed centrally through `com.chronoscore.coordinator.coordination.RedisKeyFactory`. Raw string literals are prohibited throughout application logic.

| Key Pattern | Type | Purpose | TTL |
| :--- | :--- | :--- | :--- |
| `chronos:leader:lock` | String | Active cluster leadership lease holding the winner's coordinator ID | 5 seconds (configurable) |
| `chronos:leader:epoch` | String (Integer) | Monotonically increasing 64-bit generation counter incremented on each election term | Persistent (no TTL) |
| `chronos:task:lock:{resourceId}` | String | Distributed task lock holding the unique acquisition owner UUID token | 30 seconds (configurable) |

---

## 3. Lease-Based Leader Election

### 3.1 Election Mechanics
Each coordinator instance runs an autonomous election loop managed by `RedisLeaderElectionService`:
1. **Acquisition**: A follower attempts atomic creation of the leader lock key:
   ```text
   SET chronos:leader:lock <coordinator-id> NX EX 5
   ```
2. **Epoch Assignment**: If the key is successfully created, the coordinator becomes `LEADER` and immediately increments the global epoch:
   ```text
   INCR chronos:leader:epoch
   ```
   The returned 64-bit integer becomes the leader's active `leaderEpoch`.
3. **Standby Follower**: If the key already exists (owned by another coordinator), the coordinator remains in the `FOLLOWER` state.

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

### 3.2 Atomic Lease Renewal
The active leader periodically executes a scheduled renewal tick (every 1.5 seconds by default) before the 5-second lease expires.

**Strict Ownership Check**: The renewal MUST NOT blindly extend the TTL. An atomic Lua script verifies that the coordinator ID currently stored in Redis matches the caller before resetting the TTL:

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('expire', KEYS[1], ARGV[2])
else
    return 0
end
```

If the script returns `0`, the coordinator detects immediate ownership loss, transitions to `LEADERSHIP_LOST`, and notifies all registered `LeadershipStateListener` callbacks.

### 3.3 Atomic Safe Release on Graceful Shutdown
When a coordinator node terminates (via Spring `SmartLifecycle.stop()` or shutdown hooks), it releases its lease using an ownership-checked atomic Lua script:

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

**Why this is mandatory**: If coordinator A experienced a slow shutdown and coordinator B already claimed the expired lease, an unconditional `DEL` would destroy coordinator B's active leadership lease, causing unnecessary cluster instability.

---

## 4. Leader Epochs & Generational Fencing

### 4.1 The Stale Leader Hazard (Zombie Coordinator)
In distributed environments, coordinators can experience stop-the-world JVM garbage collection pauses, OS paging delays, or network partitions:

```
Coordinator A (Leader, Epoch 1)
     ↓
Stop-the-world GC pause (6 seconds)
     ↓
Redis lease TTL expires (5 seconds)
     ↓
Coordinator B acquires lease (`SET NX EX 5`)
     ↓
Leader epoch increments: Epoch 2
Coordinator B is now active leader
     ↓
Coordinator A resumes from GC pause
     ↓
Coordinator A erroneously believes it is still leader
     ↓
Attempts downstream dispatch or task updates
```

Without generational fencing, Coordinator A could execute stale scheduling decisions concurrently with Coordinator B, resulting in split-brain hazards.

### 4.2 Generational Fencing Protection
1. Each leadership transition issues a strictly higher leader epoch via `INCR chronos:leader:epoch`.
2. All leader actions carry a `FencingToken(coordinatorId, epoch, issuedAt)`.
3. The `FencingTokenService` validates incoming tokens:
   - Verifies the local node is still in the `LEADER` state with a valid lease.
   - Verifies the token's epoch matches the current cluster epoch (`epoch == clusterEpoch`).
   - If `epoch < clusterEpoch`, the operation is aborted with `StaleLeaderException`.

### 4.3 Task.version vs. Leader Epoch
It is vital to distinguish between database row versions and cluster leader epochs:

| Property | `Task.version` | `leaderEpoch` |
| :--- | :--- | :--- |
| **Layer** | PostgreSQL Persistence Tier | Distributed Coordination Tier |
| **Scope** | Individual Task entity row | Cluster-wide leadership generation |
| **Storage Engine** | PostgreSQL (`chronos_tasks`) | Redis (`chronos:leader:epoch`) |
| **Purpose** | Prevents concurrent lost updates on a specific task | Prevents stale/partitioned coordinators from performing leadership operations |
| **Increment Trigger** | On every state transition of that specific task | Only upon a new coordinator leadership acquisition term |

---

## 5. Distributed Task Locks

Chronos Core provides a reusable distributed lock abstraction (`DistributedLockService` and `RedisDistributedLockService`) to guarantee mutual exclusion when operating on tasks.

### 5.1 Lock Semantics
- **Unique Owner Tokens**: Every acquisition generates a fresh cryptographic UUID (`UUID.randomUUID().toString()`). This ensures that multiple sequential or concurrent attempts by the same coordinator against the same task remain strictly distinguishable.
- **Atomic Acquisition**:
  ```text
  SET chronos:task:lock:{resourceId} <owner-uuid> NX EX <ttl-seconds>
  ```
  Executed in a single atomic command.
- **Safe Release Protection**:
  ```lua
  if redis.call('get', KEYS[1]) == ARGV[1] then
      return redis.call('del', KEYS[1])
  else
      return 0
  end
  ```
  If Worker 1 acquires a lock that expires due to slow execution, and Worker 2 subsequently acquires the lock, Worker 1's eventual release call will return `false` without deleting Worker 2's active lock.
- **Lock Renewal**: Supported via `renew(LockHandle, Duration)` using atomic Lua ownership verification.

---

## 6. Coordination State Model

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

Coordinators maintain deterministic state transitions:
1. `FOLLOWER`: Standby node monitoring cluster health.
2. `LEADER`: Active cluster leader managing leases and renewals.
3. `LEADERSHIP_LOST`: Terminal transition event for the current term; triggers state invalidation and listener cleanup before reverting to `FOLLOWER`.

---

## 7. Configuration Reference

Coordination properties are configured in `application.yml` and overridable via environment variables:

| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `chronos.coordination.enabled` | `CHRONOS_COORDINATION_ENABLED` | `true` | Enable/disable coordination lifecycle |
| `chronos.coordination.coordinator-id` | `CHRONOS_COORDINATOR_ID` | Hostname / UUID | Unique coordinator node identifier |
| `chronos.coordination.leader-lease-duration` | `CHRONOS_LEADER_LEASE_DURATION` | `5s` | Leader lock key TTL in Redis |
| `chronos.coordination.leader-renewal-interval` | `CHRONOS_LEADER_RENEWAL_INTERVAL` | `1500ms` | Interval between scheduled lease renewals |
| `chronos.coordination.task-lock-ttl` | `CHRONOS_TASK_LOCK_TTL` | `30s` | Default distributed task lock expiration |
| `spring.data.redis.host` | `CHRONOS_REDIS_HOST` | `localhost` | Redis server hostname |
| `spring.data.redis.port` | `CHRONOS_REDIS_PORT` | `6379` | Redis server port |

---

## 8. Correctness Rules & Non-Claims

1. **At-Least-Once Delivery**: The system does NOT claim exactly-once execution. Reliability is achieved through at-least-once delivery, idempotent task handlers, and fencing-token stale write protection.
2. **Redis as Ephemeral Coordination**: Redis is strictly the coordination and lock store. PostgreSQL remains the single durable source of truth.
3. **No Silent Single-Node Mode**: When Redis coordination is unavailable, coordinators fail fast and log clear operational alerts rather than silently operating as uncoordinated rogue nodes.
