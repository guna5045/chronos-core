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

## 3. Future REST API Endpoints (Phase 09 Blueprint)

> **Notice**: The REST HTTP gateway endpoints are deferred to Phase 09.
> Future endpoints will expose task submission (`POST /api/v1/tasks`), retrieval (`GET /api/v1/tasks/{id}`),
> cancellation (`POST /api/v1/tasks/{id}/cancel`), and cluster topology visibility (`GET /api/v1/cluster/overview`).
