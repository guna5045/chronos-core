# Chronos Core - API & Contract Blueprints

## Status
- **Current Phase**: Phase 01 (Foundation)
- **Specification Status**: Planned Contracts

---

## 1. Coordinator Public REST Endpoints (Planned - Phase 09)

### 1.1 Tasks API
- `POST /api/v1/tasks` - Submit a new task with payload, priority, and retry policy.
- `GET /api/v1/tasks/{id}` - Retrieve task status, execution timeline, and attempt history.
- `POST /api/v1/tasks/{id}/cancel` - Request immediate cancellation of an in-flight task.
- `GET /api/v1/tasks` - Filterable paginated list of tasks by status, tag, or time range.

### 1.2 Workflows (DAGs) API
- `POST /api/v1/workflows` - Register or update a workflow definition with DAG dependencies.
- `POST /api/v1/workflows/{id}/trigger` - Trigger execution of a specific workflow instance.
- `GET /api/v1/workflows/instances/{instanceId}` - Fetch full DAG execution status graph.

### 1.3 Cluster & Worker Health API
- `GET /api/v1/cluster/overview` - Coordinator status, active leader, epoch number, and cluster statistics.
- `GET /api/v1/cluster/workers` - Enumerate active registered workers, heartbeat statuses, and load.

---

## 2. Internal gRPC Protocol Contracts (Planned - Phase 06)

### 2.1 WorkerService
```protobuf
syntax = "proto3";
package com.chronoscore.grpc;

service WorkerService {
  rpc RegisterWorker (RegisterWorkerRequest) returns (RegisterWorkerResponse);
  rpc StreamHeartbeat (stream HeartbeatPing) returns (stream HeartbeatPong);
  rpc DispatchTask (TaskDispatchPayload) returns (TaskDispatchAck);
  rpc CancelTask (TaskCancelRequest) returns (TaskCancelResponse);
}
```

---

## 3. Serialization & Encoding
- REST payloads encode in UTF-8 JSON.
- Internal RPC payloads encode in Google Protocol Buffers v3.
- Timestamps conform strictly to ISO-8601 (UTC).
