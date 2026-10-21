# Chronos Core - Worker Cluster & gRPC Communication Specification

## Status
- **Current Phase**: Phase 05 (Worker Cluster & gRPC Communication)
- **Document Version**: 0.5.0

---

## 1. Architectural Foundation & Overview

Chronos Core decouples coordination, scheduling, and metadata management from distributed task execution. Coordinator instances manage cluster topology, scheduling decisions, and state machine transitions, while distributed Worker nodes execute task payloads in isolated Java 21 virtual threads.

All communication between Coordinators and Worker instances occurs over high-performance **gRPC and Protocol Buffers**, establishing a clean network boundary without sharing database connections or filesystem state.

```text
                           COORDINATOR
               ┌─────────────────────────────────┐
               │    TaskCoordinationGrpcService   │
               │         (Port 9090)             │
               │   ┌─────────────────────────┐   │
               │   │     WorkerRegistry      │   │
               │   │  (In-Memory Concurrent) │   │
               │   └─────────────────────────┘   │
               │   ┌─────────────────────────┐   │
               │   │   WorkerHealthMonitor   │   │
               │   │   (3s/6s Liveness Tick) │   │
               │   └─────────────────────────┘   │
               └────────────────┬────────────────┘
                                │
                         gRPC / Protobuf
               ┌────────────────┼────────────────┐
               │                │                │
               ▼                ▼                ▼
        ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
        │   Worker A   │ │   Worker B   │ │   Worker C   │
        │  (Port 9091) │ │  (Port 9092) │ │  (Port 9093) │
        │              │ │              │ │              │
        │ TaskExecutor │ │ TaskExecutor │ │ TaskExecutor │
        │ (Virtual Th) │ │ (Virtual Th) │ │ (Virtual Th) │
        └──────────────┘ └──────────────┘ └──────────────┘
```

> **Precedence Rule & Architectural Accuracy**:  
> - **Execution Semantics**: At-least-once delivery with idempotency and fencing-based stale-write protection.  
> - **In-Memory Registry**: Worker registration state and heartbeats are held in-memory in the active coordinator's `WorkerRegistry`. High-frequency heartbeat writes are strictly prohibited from hitting Redis or PostgreSQL to protect datastore throughput.  
> - **No Direct DB Access**: Worker nodes do not connect to PostgreSQL. All state updates and task results are reported exclusively back to the Coordinator via gRPC.

---

## 2. Worker Lifecycle & Registration Handshake

### 2.1 Registration Protocol
When a Worker daemon starts, it discovers the Coordinator endpoint (`coordinator.host` and `coordinator.grpc-port`) and initiates a synchronous registration RPC:

```protobuf
rpc RegisterWorker (ProtoWorkerRegistrationRequest) returns (ProtoWorkerRegistrationResponse);
```

The request delivers immutable worker metadata:
- `worker_id`: Unique identifier of the worker node (e.g., `worker-alpha-01`).
- `hostname` & `port`: Network coordinates where the Worker's own `WorkerService` gRPC server is listening.
- `max_concurrency`: Maximum simultaneous task capacity.
- `supported_task_types`: List of task execution capabilities (e.g., `SHELL`, `HTTP`, `CONTAINER`).
- `process_id`: OS process ID running the worker instance.

### 2.2 Session ID Lifecycle
The Coordinator processes registration via `InMemoryWorkerRegistry.registerWorker(...)`:
1. Validates worker parameters (must be positive capacity, non-blank ID, valid port).
2. Generates an epochal, cryptographically unique `sessionId` (UUID v4).
3. Initializes the worker in `ACTIVE` state with full capacity available (`activeTasks = 0`).
4. Returns the `sessionId` in the `ProtoWorkerRegistrationResponse`.
5. The Worker caches this `sessionId` for all subsequent heartbeats and result reports. If a Worker restarts or re-registers, a new `sessionId` is assigned, immediately invalidating the previous session.

---

## 3. Bidirectional Streaming Heartbeats & Liveness Detection

### 3.1 Streaming Heartbeat Channel
Rather than issuing repetitive unary RPC calls, the Worker establishes a persistent HTTP/2 bidirectional stream via gRPC:

```protobuf
rpc StreamHeartbeat (stream ProtoHeartbeatPing) returns (stream ProtoHeartbeatPong);
```

- **Ping Interval**: Worker sends a `ProtoHeartbeatPing` every 1,000 milliseconds (configurable via `chronos.worker.heartbeat.interval-ms`).
- **Ping Payload**: Contains `worker_id`, `session_id`, `timestamp`, `active_tasks`, and `available_capacity`.
- **Pong Response**: Coordinator responds with `ProtoHeartbeatPong` containing `session_id`, `acknowledged_timestamp`, and `leader_epoch`.

### 3.2 Failure Detection State Machine
The Coordinator runs a background `WorkerHealthMonitor` every 1,000 milliseconds. Worker health transitions through four deterministic states:

```text
               Register
                  │
                  ▼
          ┌──────────────┐
          │  REGISTERING │
          └───────┬──────┘
                  │  First Heartbeat
                  ▼
  ┌──────────► ┌──────┐ ◄──────────┐
  │  Heartbeat │ACTIVE│            │ Valid Heartbeat
  │  < 3s      └──┬───┘            │
  └───────────────┘  │             │
                     │ Missed HB   │
                     │ > 3s        │
                     ▼             │
               ┌───────────┐       │
               │ SUSPECTED ├───────┘
               └─────┬─────┘
                     │ Missed HB
                     │ > 6s
                     ▼
                ┌─────────┐
                │ REMOVED │
                └─────────┘
```

1. **`REGISTERING`**: Initial state upon registration before the first heartbeat ping is received.
2. **`ACTIVE`**: Heartbeats received within the 3,000ms threshold (`chronos.cluster.worker-suspect-timeout-ms`). Full dispatch eligibility.
3. **`SUSPECTED`**: No heartbeat received for > 3,000ms. The worker is temporarily excluded from new task dispatch.
4. **Recovery (`SUSPECTED` -> `ACTIVE`)**: If a valid heartbeat ping arrives while in `SUSPECTED` state, the worker immediately recovers to `ACTIVE` without requiring a full re-registration cycle.
5. **`REMOVED`**: No heartbeat received for > 6,000ms (`chronos.cluster.worker-eviction-timeout-ms`). The worker is permanently removed from the active cluster registry. All in-flight tasks assigned to this worker become candidates for failure recovery and reassignment.

---

## 4. Stale Session Protection & Split-Brain Mitigation

In distributed environments, network partitions, TCP connection hangs, or process pausing can cause stale connections to deliver delayed packets.

Chronos Core enforces **Session-Guarded Invalidation**:
1. Every registration generates a new `sessionId`.
2. Heartbeats, task status updates, and result reports MUST match the registered `sessionId` of the active `WorkerNode`.
3. If a heartbeat or state update is received with a mismatched `sessionId`:
   - The coordinator rejects the update immediately.
   - The stream returns `acknowledged = false` with an explicit stale session error code (`STALE_SESSION`).
   - The stale worker is instructed to immediately re-register.

---

## 5. Task Dispatch & Execution Model

### 5.1 Coordinator-to-Worker Task Dispatch
When the Coordinator dispatches a task to an active worker, it calls the Worker's `WorkerService` over gRPC:

```protobuf
rpc DispatchTask (ProtoTaskAssignment) returns (ProtoDispatchTaskResponse);
rpc CancelTask (ProtoCancelTaskRequest) returns (ProtoCancelTaskResponse);
```

- **Call Deadlines**: All dispatch and cancellation calls enforce explicit gRPC deadlines (default 5,000ms) via `WorkerDispatchClient`.
- **Immediate Acceptance**: `DispatchTask` is non-blocking. The Worker checks its local capacity:
  - If capacity is available, it responds with `accepted = true` and submits the task asynchronously to the virtual thread executor.
  - If capacity is saturated, it responds with `accepted = false` and rejection reason `CAPACITY_EXHAUSTED`.

### 5.2 Java 21 Virtual Thread Execution Engine
Task execution on Worker instances is powered by `VirtualThreadTaskExecutor`:
- **Unbounded Lightweight Concurrency**: Uses `Executors.newVirtualThreadPerTaskExecutor()`, allowing blocking operations (I/O, network calls, sleeps) without thread starvation.
- **Bounded Capacity Gate**: Regulated by atomic CAS operations (`activeTasks.getAndUpdate(...)`) up to `maxConcurrency`.
- **Preemption & Cancellation**: Long-running or timed-out tasks can be cancelled via `CancelTask`, which sets a cancellation flag on the running task context and interrupts the assigned virtual thread.

### 5.3 Asynchronous Result Reporting
Upon completion, failure, or cancellation of a task, the Worker reports the result back to the Coordinator via `TaskCoordinationService.ReportTaskResult`:

```protobuf
rpc ReportTaskResult (ProtoReportTaskResultRequest) returns (ProtoReportTaskResultResponse);
```

- The request includes `task_id`, `attempt_number`, `status` (`SUCCEEDED`, `FAILED`, `CANCELLED`), `output_payload`, `error_code`, `error_message`, and execution timing timestamps.
- The Coordinator validates the session ID and leader epoch, updates the durable PostgreSQL task record, and acknowledges the report.

---

## 6. Graceful Shutdown & Lifecycle Management

Both Coordinator and Worker participate in Spring's `SmartLifecycle` framework:

### Coordinator Shutdown
1. Stops accepting new worker registrations and gRPC connections.
2. Closes active heartbeat bidirectional streams.
3. Drains in-flight coordination RPCs with a 5-second deadline.
4. Releases Redis leader lease safely via ownership-checked Lua script.

### Worker Shutdown
1. Signals cancellation to all active virtual threads.
2. Waits up to 3 seconds for active tasks to conclude.
3. Closes coordinator gRPC client channels.
4. Terminates the worker's Netty gRPC server cleanly.

---

## 7. Configuration Reference

### Coordinator Cluster Settings (`chronos.cluster`)
```yaml
chronos:
  cluster:
    grpc-port: 9090
    worker-heartbeat-timeout-ms: 1000
    worker-suspect-timeout-ms: 3000
    worker-eviction-timeout-ms: 6000
    dispatch-timeout-ms: 5000
```

### Worker Settings (`chronos.worker`)
```yaml
chronos:
  worker:
    worker-id: worker-local-01
    hostname: localhost
    port: 9091
    max-concurrency: 16
    task-types:
      - COMPUTE
      - SHELL
      - HTTP
    coordinator:
      host: localhost
      grpc-port: 9090
    heartbeat:
      interval-ms: 1000
      timeout-ms: 3000
```
