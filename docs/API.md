# Chronos Core - API & Shared Contracts Specification

## Status
- **Current Phase**: Phase 02 (Core Domain Model & Shared Contracts)
- **Specification Status**: Active Domain Contracts & Protocol Buffer Wire Schemas
- **REST API Status**: *Deferred to Phase 09 (Planned)*

---

## 1. Domain Data Transfer Objects (`com.chronoscore.common.dto`)

Shared DTO records used for safe data transfer across service boundaries without exposing internal entity mechanics:

### 1.1 `TaskSubmissionDTO`
Payload for creating a new task submission:
- `taskType` (String, required): Identified worker capability to invoke.
- `payload` (String, required): Structured JSON or textual payload for the task.
- `priority` (TaskPriority, optional, default: `NORMAL`): Execution urgency.
- `tenantId` (String, required): Tenant partition identifier.
- `idempotencyKey` (String, optional): Deduplication key (max 256 chars).
- `scheduledAt` (Instant, optional): Future execution time.
- `deadline` (Instant, optional): Absolute deadline.
- `timeout` (Duration, optional): Maximum attempt execution duration.
- `maxAttempts` (Integer, optional, default: 3): Max execution retries.

### 1.2 `TaskSummaryDTO`
Lightweight representation for cluster listings and status inspection:
- `taskId` (UUID)
- `taskType` (String)
- `priority` (TaskPriority)
- `status` (TaskStatus)
- `tenantId` (String)
- `createdAt` (Instant)
- `currentAttempt` (int)
- `maxAttempts` (int)
- `version` (long)

### 1.3 `TaskStateChangeDTO`
Event structure emitted upon successful lifecycle state transitions:
- `taskId` (UUID)
- `previousStatus` (TaskStatus)
- `newStatus` (TaskStatus)
- `currentAttempt` (int)
- `timestamp` (Instant)
- `reason` (String)

### 1.4 `ExecutionAttemptSummaryDTO`
Summary of an individual attempt executed by a worker:
- `attemptId` (UUID)
- `taskId` (UUID)
- `workerId` (String)
- `attemptNumber` (int)
- `status` (ExecutionAttemptStatus)
- `startedAt` (Instant)
- `completedAt` (Instant, nullable)
- `errorCode` (String, nullable)

---

## 2. Protocol Buffers Wire Contract (`src/main/proto/chronos_task_execution.proto`)

Binary serialization schema used for inter-node communication between Coordinators and Workers:

### 2.1 Core Messages
- `ProtoTask`: Wire representation of a task definition.
- `ProtoTaskAssignment`: Dispatch payload delivered to an assigned worker node.
- `ProtoTaskResult`: Result payload reported by a worker upon completion or failure.
- `ProtoTaskStateUpdate`: Real-time state transition telemetry.
- `ProtoWorkerIdentity`: Worker capability and connection metadata.
- `ProtoHeartbeatPing` / `ProtoHeartbeatPong`: Liveness and cluster load telemetry with epoch fencing tokens.
- `ProtoTaskCancelRequest` / `ProtoTaskCancelResponse`: Explicit cancellation messaging.

### 2.2 Wire Service Signatures (Runtime Implementation Deferred to Phase 06)
```protobuf
service WorkerService {
  rpc RegisterWorker (ProtoWorkerIdentity) returns (ProtoHeartbeatPong);
  rpc StreamHeartbeat (stream ProtoHeartbeatPing) returns (stream ProtoHeartbeatPong);
  rpc DispatchTask (ProtoTaskAssignment) returns (ProtoTaskStateUpdate);
  rpc CancelTask (ProtoTaskCancelRequest) returns (ProtoTaskCancelResponse);
}

service TaskCoordinationService {
  rpc UpdateTaskState (ProtoTaskStateUpdate) returns (ProtoHeartbeatPong);
  rpc ReportTaskResult (ProtoTaskResult) returns (ProtoHeartbeatPong);
}
```

---

## 3. Persistence Service Contracts (`com.chronoscore.coordinator.persistence`)

Introduced in Phase 03 as internal coordinator application services:

### 3.1 `TransactionalTaskService`
Transactional coordinator service enforcing atomic ACID persistence across state changes:
- `Task submitTask(Task task, String reason, String metadata)`: Atomically commits task row, audit record, and `TaskSubmitted` outbox event.
- `Task transitionTask(UUID taskId, TaskStatus targetStatus, String reason, String metadata)`: Validates transition with `TaskStateMachine`, updates task with optimistic locking, logs audit record, and stages `TaskStatusChanged` outbox event.
- `ExecutionAttempt startExecutionAttempt(UUID taskId, String workerId, int attemptNumber)`: Starts attempt, transitions task through `DISPATCHED` to `RUNNING`, records attempt, audit, and outbox event.
- `ExecutionAttempt completeExecutionAttempt(UUID attemptId, boolean success, String errorCode, String errorMessage)`: Completes attempt, determines success or retry/DLQ status, updates attempt, task, audit, and outbox event.

### 3.2 Repositories
- `TaskRepository`: CRUD operations, `findByIdForUpdate` (`SELECT ... FOR UPDATE`), optimistic version checks, `findByIdempotencyKey`, and `findQueuedTasks`.
- `ExecutionAttemptRepository`: Preserves execution attempts history, lookup by task and sequence number.
- `TaskAuditRepository`: Append-only recording of state transitions and metadata.
- `OutboxRepository`: Outbox event staging, pending event polling, and publication acknowledgement.

---

## 4. Distributed Coordination Service Contracts (`com.chronoscore.coordinator.coordination`)

Introduced in Phase 04 as foundational cluster coordination abstractions:

### 4.1 `LeaderElectionService`
Lease-based leader election interface:
- `boolean tryAcquireLeadership()`: Atomically attempts lease acquisition (`SET NX EX 5`) and increments epoch (`INCR`).
- `boolean renewLease()`: Periodically renews lease TTL via atomic Lua script verifying ownership.
- `boolean releaseLeadership()`: Safely deletes lease via atomic Lua script verifying ownership on shutdown.
- `boolean isLeader()`: Returns true if the coordinator currently holds a valid leadership lease.
- `long getCurrentEpoch()`: Returns the active leader epoch or 0 if follower.
- `LeaderIdentity getLeadershipIdentity()`: Returns immutable leadership snapshot (`coordinatorId`, `leaderEpoch`, `state`, `leaseExpiresAt`).
- `void addListener(LeadershipStateListener listener)`: Registers callbacks for leadership acquisition and loss events.

### 4.2 `DistributedLockService`
Reusable distributed task lock interface:
- `Optional<LockHandle> tryAcquire(String resource, Duration ttl)`: Atomically acquires lock with unique owner UUID (`SET NX EX`).
- `boolean release(LockHandle handle)`: Safely releases lock via atomic Lua ownership check.
- `boolean renew(LockHandle handle, Duration ttl)`: Atomically extends lock TTL via Lua script.
- `boolean isOwner(LockHandle handle)`: Verifies whether the specified handle still owns the lock in Redis.

### 4.3 `FencingTokenService`
Generational leader epoch fencing service:
- `FencingToken getCurrentFencingToken()`: Returns an active fencing token with leader identity and epoch.
- `void validateFencingToken(FencingToken token)`: Validates that the token's epoch matches the latest cluster generation and throws `StaleLeaderException` if stale.

---

## 5. Future REST API Endpoints (Phase 09 Blueprint)

> **Notice**: The REST HTTP gateway endpoints are deferred to Phase 09.
> Future endpoints will expose task submission (`POST /api/v1/tasks`), retrieval (`GET /api/v1/tasks/{id}`),
> cancellation (`POST /api/v1/tasks/{id}/cancel`), and cluster topology visibility (`GET /api/v1/cluster/overview`).


