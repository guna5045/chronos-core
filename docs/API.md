# Chronos Core - API & Shared Contracts Specification

## Status
- **Current Phase**: Phase 08 (Workflow & DAG Orchestration)
- **Specification Status**: Active Domain Contracts, Wire Schemas, Coordination Services, Scheduling Interfaces, Fault Tolerance / Recovery Services, and Workflow & DAG Orchestration Services
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
- `ProtoDispatchTaskResponse`: Worker response indicating acceptance or capacity exhaustion.
- `ProtoTaskResult`: Result payload reported by a worker upon completion or failure.
- `ProtoReportTaskResultRequest` / `ProtoReportTaskResultResponse`: Result reporting envelopes.
- `ProtoTaskStateUpdate` / `ProtoTaskStateUpdateResponse`: Real-time state transition telemetry.
- `ProtoWorkerIdentity`: Worker capability and connection metadata.
- `ProtoWorkerRegistrationRequest` / `ProtoWorkerRegistrationResponse`: Registration handshake with assigned `sessionId`.
- `ProtoHeartbeatPing` / `ProtoHeartbeatPong`: Liveness and cluster load telemetry with epoch fencing tokens and session IDs.
- `ProtoCancelTaskRequest` / `ProtoCancelTaskResponse`: Explicit cancellation messaging.

### 2.2 Wire Service Signatures (Implemented in Phase 05)
```protobuf
service TaskCoordinationService {
  rpc RegisterWorker (ProtoWorkerRegistrationRequest) returns (ProtoWorkerRegistrationResponse);
  rpc StreamHeartbeat (stream ProtoHeartbeatPing) returns (stream ProtoHeartbeatPong);
  rpc UpdateTaskState (ProtoTaskStateUpdate) returns (ProtoTaskStateUpdateResponse);
  rpc ReportTaskResult (ProtoReportTaskResultRequest) returns (ProtoReportTaskResultResponse);
}

service WorkerService {
  rpc DispatchTask (ProtoTaskAssignment) returns (ProtoDispatchTaskResponse);
  rpc CancelTask (ProtoCancelTaskRequest) returns (ProtoCancelTaskResponse);
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

## 5. Worker Cluster Coordination & Runtime Contracts

Introduced in Phase 05 for coordinator-worker topology management and task execution:

### 5.1 `WorkerRegistry` (`com.chronoscore.coordinator.cluster`)
Thread-safe in-memory cluster registry maintaining active worker node sessions:
- `WorkerNode registerWorker(String workerId, String hostname, int port, int maxConcurrency, List<String> capabilities, long processId)`: Atomically registers a worker and returns a new session ID.
- `boolean recordHeartbeat(String workerId, String sessionId, int activeTasks, int availableCapacity)`: Updates liveness timestamp and capacity; rejects stale sessions.
- `Optional<WorkerNode> getWorker(String workerId)`: Retrieves snapshot of a registered worker.
- `List<WorkerNode> getActiveWorkers()`: Returns all active workers available for dispatch.
- `List<WorkerNode> getAllWorkers()`: Returns all registered nodes regardless of state.
- `boolean updateWorkerState(String workerId, WorkerState newState)`: Transitions worker state (`REGISTERING`, `ACTIVE`, `SUSPECTED`, `REMOVED`).
- `boolean removeWorker(String workerId)`: Evicts worker from in-memory tracking.

### 5.2 `WorkerDispatchClient` (`com.chronoscore.coordinator.cluster`)
Coordinator dispatch client communicating with worker gRPC endpoints:
- `ProtoDispatchTaskResponse dispatchTask(WorkerNode worker, ProtoTaskAssignment assignment)`: Sends task assignment with 5-second deadline.
- `ProtoCancelTaskResponse cancelTask(WorkerNode worker, String taskId, String reason)`: Sends cooperative cancellation request with 5-second deadline.

### 5.3 `TaskExecutor` (`com.chronoscore.worker.execution`)
Java 21 Virtual Thread-backed task execution runtime on worker instances:
- `CompletableFuture<TaskExecutionResult> executeTask(ProtoTaskAssignment assignment)`: Submits task to virtual thread; enforces atomic capacity gating.
- `boolean cancelTask(String taskId, String reason)`: Signals cooperative cancellation and interrupts running virtual thread.
- `int getActiveTaskCount()` / `int getMaxConcurrency()` / `boolean hasAvailableCapacity()`: Capacity interrogation.
- `void shutdown()`: Cooperative termination draining active virtual threads.

### 5.4 `CoordinatorClient` (`com.chronoscore.worker.grpc`)
Worker client interface communicating with Coordinator:
- `ProtoWorkerRegistrationResponse register(ProtoWorkerRegistrationRequest request)`: Performs initial registration handshake.
- `StreamObserver<ProtoHeartbeatPing> openHeartbeatStream(StreamObserver<ProtoHeartbeatPong> responseObserver)`: Establishes bidirectional streaming heartbeat.
- `ProtoReportTaskResultResponse reportResult(ProtoReportTaskResultRequest request)`: Submits terminal attempt outcomes to coordinator.

---

## 6. Distributed Scheduling & Task Distribution Contracts

Introduced in Phase 06 for leader-aware scheduling, consistent hash routing, and worker selection:

### 6.1 `TaskScheduler` (`com.chronoscore.coordinator.scheduler`)
Leader-aware batch scheduling engine:
- `SchedulingResult scheduleBatch()`: Executes a single scheduling iteration; asserts fencing tokens, selects ready tasks, acquires distributed locks, resolves workers, and dispatches via gRPC.
- `void start()`: Starts the periodic scheduling polling loop on the leader node.
- `void stop()`: Cooperatively stops the scheduling loop.
- `boolean isRunning()`: Returns true if the scheduling loop is actively running.

### 6.2 `WorkerSelector` (`com.chronoscore.coordinator.distribution`)
Deterministic worker resolution abstraction:
- `Optional<WorkerNode> selectWorker(String taskKey)`: Resolves an active worker node with available capacity for the specified task key using the consistent hash ring.

### 6.3 `ConsistentHashRing` (`com.chronoscore.coordinator.distribution`)
Reusable consistent hash ring implementation:
- `void addWorker(String workerId)`: Hashes 128 virtual nodes for the worker and inserts them into the ring.
- `void removeWorker(String workerId)`: Removes all 128 virtual nodes for the worker from the ring.
- `Optional<String> getWorker(String key)`: Returns the primary worker owning the specified key.
- `List<String> getCandidates(String key)`: Returns an ordered list of distinct worker candidates starting clockwise from the hash position with ring wrap-around.
- `void reconcile(Collection<String> activeWorkerIds)`: Synchronizes ring membership with the current set of eligible active workers.
- `int getWorkerCount()` / `int getVirtualNodeCount()`: Ring topology inspection.

### 6.4 `SchedulerMetricsRecorder` (`com.chronoscore.coordinator.scheduler`)
Observability SPI hook interface for metrics collection:
- `void recordPoll()`: Increments scheduler poll iteration count.
- `void recordTasksSelected(int count)`: Records count of tasks retrieved from database.
- `void recordDispatchOutcome(DispatchOutcome outcome)`: Records count per dispatch outcome (`DISPATCHED`, `DEADLINE_EXPIRED`, `LOCKED_BY_OTHER`, `SKIPPED_NOT_QUEUED`, `NO_WORKER_AVAILABLE`, `DISPATCH_REJECTED`).
- `void recordDispatchLatency(Duration latency)`: Tracks gRPC dispatch latency.
- `void recordQueueDepth(int depth)`: Tracks pending queue depth.

---

---

## 7. Fault Tolerance, Recovery & Lease Service Contracts

Introduced in Phase 07 for execution leases, failure recovery, retry policies, and dead-letter handling:

### 7.1 `ExecutionLeaseService` (`com.chronoscore.coordinator.lease`)
Task execution lease tracking interface:
- `TaskExecutionLease acquireLease(UUID taskId, UUID attemptId, String workerId, long fenceToken, Duration ttl)`: Atomically stores or updates execution lease with fencing token.
- `boolean renewLease(UUID taskId, UUID attemptId, Duration extension)`: Extends the lease expiry time if the specified attempt currently holds the lease.
- `boolean releaseLease(UUID taskId)`: Deletes the task execution lease upon task completion.
- `Optional<TaskExecutionLease> getLease(UUID taskId)`: Retrieves the active lease for a task.
- `List<TaskExecutionLease> findExpiredLeases(Instant asOf, int limit)`: Returns active leases whose expiration is before `asOf`.
- `List<TaskExecutionLease> findLeasesByWorker(String workerId)`: Returns active leases assigned to a specific worker.

### 7.2 `RetryPolicy` (`com.chronoscore.coordinator.recovery`)
Failure classification and retry decision engine:
- `boolean shouldRetry(Task task, String errorCode)`: Determines if an attempt failure qualifies for retry based on `currentAttempt < maxAttempts` and `FailureClassification`.
- `Duration calculateBackoff(int attempt)`: Computes bounded exponential backoff delay with configured jitter strategy (`delay = min(maxDelay, baseDelay * 2^(attempt-1))`).
- `Instant nextScheduledTime(int attempt)`: Computes wall-clock scheduled time for the next retry attempt.
- `boolean isRetryable(String errorCode)`: Classifies error code into retryable vs permanent.

### 7.3 `TaskRecoveryService` (`com.chronoscore.coordinator.recovery`)
Coordinator-side failure recovery and stale result rejection service:
- `boolean handleTaskCompletion(UUID taskId, UUID attemptId, boolean success, String errorCode, String errorMessage, long fenceToken)`: Evaluates terminal task result; enforces strict attempt fencing and rejects stale worker completions; applies retry policy or DLQ routing on failure.
- `int recoverWorkerFailure(String workerId)`: Evaluates all active tasks assigned to an evicted worker, marks orphaned attempts failed (`WORKER_UNAVAILABLE`), and requeues with backoff or moves to DLQ.
- `int reclaimExpiredLeases(int batchSize)`: Scans for tasks with expired leases, reclaims orphaned tasks, and applies retry/DLQ transitions.

### 7.4 `TaskTimeoutService` (`com.chronoscore.coordinator.recovery`)
Task execution timeout enforcement service:
- `int scanForTimeouts(int batchSize)`: Identifies running tasks whose execution time exceeds configured `task.timeout`, dispatches cooperative cancellation to the worker, and triggers failure recovery.

### 7.5 `DlqService` (`com.chronoscore.coordinator.recovery`)
Dead-Letter Queue management and replay service:
- `Task replayTask(UUID taskId)`: Atomically resets a task in `DLQ` state back to `QUEUED` with zeroed attempt count, clears failure metadata, stages audit/outbox events, and makes it eligible for rescheduling.
- `List<Task> listDlqTasks(int limit)`: Retrieves tasks currently in `DLQ` state.
- `Optional<Task> getDlqTask(UUID taskId)`: Retrieves a specific DLQ task if present.

---

## 8. Workflow Orchestration Contracts (`com.chronoscore.coordinator.workflow`)

Phase 08 introduces the workflow orchestration layer for Directed Acyclic Graph (DAG) task execution:

### 8.1 `WorkflowDagValidator` (`com.chronoscore.common.validation`)
Deterministic DAG validation and topological sorting:
- `validate(WorkflowVersion version)`: Validates graph constraints, cycle detection (DFS 3-coloring), orphaned dependencies, duplicate IDs, and graph bounds. Throws `WorkflowValidationException`.
- `topologicalSort(WorkflowVersion version)`: Produces deterministic execution ordering via Kahn's algorithm with lexicographical tie-breaking.
- `findRootNodes(WorkflowVersion version)`: Resolves all nodes with in-degree 0.

### 8.2 `WorkflowService` (`com.chronoscore.coordinator.workflow.service`)
Workflow definition and version lifecycle service:
- `WorkflowDefinition createWorkflow(String name, String description)`: Registers a new workflow namespace.
- `WorkflowVersion createDraftVersion(UUID workflowId, List<WorkflowNodeDefinition> nodes, List<WorkflowEdge> edges)`: Creates an editable draft version.
- `WorkflowVersion publishVersion(UUID versionId)`: Validates DAG and transitions version to `PUBLISHED` (immutable).
- `WorkflowVersion deprecateVersion(UUID versionId)`: Deprecates version from starting new executions.
- `Optional<WorkflowVersion> getLatestPublishedVersion(UUID workflowId)`: Retrieves active version.

### 8.3 `WorkflowExecutionService` (`com.chronoscore.coordinator.workflow.service`)
Workflow execution and dependency propagation service:
- `WorkflowExecution startExecution(UUID workflowId, UUID versionId, String tenantId)`: Instantiates a workflow execution, binds it to the specified version, and activates root nodes as queued Chronos tasks.
- `boolean onTaskCompleted(UUID taskId, boolean success, String errorCode, String errorMessage)`: Processes task completions; atomically claims and activates eligible downstream nodes; transitions workflow to `SUCCEEDED` or `FAILED`.
- `WorkflowExecution cancelExecution(UUID executionId, String reason)`: Cancels in-flight execution, cancels pending nodes, and issues cooperative cancellation to active workers.
- `Optional<WorkflowExecution> getExecution(UUID executionId)`: Retrieves execution instance state.
- `List<WorkflowExecutionNode> getExecutionNodes(UUID executionId)`: Retrieves node execution records.

---

## 9. Future REST API Endpoints (Phase 09 Blueprint)

> **Notice**: The REST HTTP gateway endpoints are deferred to Phase 09.
> Future endpoints will expose task submission (`POST /api/v1/tasks`), retrieval (`GET /api/v1/tasks/{id}`),
> cancellation (`POST /api/v1/tasks/{id}/cancel`), DLQ replay (`POST /api/v1/tasks/{id}/replay`),
> workflow definition (`POST /api/v1/workflows`), workflow execution (`POST /api/v1/workflows/{id}/executions`),
> and cluster topology visibility (`GET /api/v1/cluster/overview`).




