# Chronos Core - Architectural Specification

## Status
- **Current Phase**: Phase 01 (Foundation)
- **Document Version**: 0.1.0-draft

---

## 1. High-Level System Architecture

Chronos Core is architected as a distributed, partitioned, master-worker platform optimized for durability and high throughput.

```
                    ┌─────────────────────────┐
                    │ Engineering Control     │
                    │ Plane (Dashboard)       │
                    └────────────┬────────────┘
                                 │ HTTP / REST
                    ┌────────────▼────────────┐
                    │   Chronos Coordinator   │◄──────┐
                    │   (Active Leader)       │       │
                    └──────┬──────┬─────┬─────┘       │ Redis Lease Election
                           │      │     │             │
        Task Dispatch / gRPC      │     └─────────────┼───────────────┐
                           │      │                   │               │
        ┌──────────────────┘      │ PostgreSQL        ▼               ▼
        │                         │ (Durable DB)  ┌────────┐     ┌────────┐
        ▼                         │               │ Redis  │     │ Standby│
┌──────────────┐                  ▼               │ Lease  │     │ Coord. │
│Chronos Worker│           ┌──────────────┐       │ Store  │     └────────┘
│   Node 1     │           │ Task & DAG   │       └────────┘
└──────────────┘           │ Audit Records│
        ▲                  └──────────────┘
        │
┌──────────────┐
│Chronos Worker│
│   Node 2     │
└──────────────┘
```

---

## 2. Core Subsystems

### 2.1 Chronos Coordinator
- **Leader Election**: Dynamic lease-based election managed through Redis key TTLs with monotonically increasing epoch numbers.
- **Fencing Tokens**: Every elected leader receives an incremented epoch counter. Any writes to PostgreSQL or Redis containing an outdated token are rejected.
- **Scheduler & State Machine**: Implements strict deterministic state transitions for tasks (`PENDING` -> `SCHEDULED` -> `DISPATCHED` -> `RUNNING` -> `COMPLETED` / `FAILED` / `RETRYING` / `DEAD_LETTER`).
- **DAG Orchestrator**: Evaluates dependency trees and executes ready task nodes concurrently using Java 21 virtual threads.

### 2.2 Chronos Worker
- **Dynamic Registration**: On boot, workers announce capabilities, resource capacities, and listen address to the active coordinator.
- **Bidirectional Streaming gRPC**: Persistent channel for heartbeats, status updates, task cancellation signals, and metrics.
- **Local Task Runner**: Virtual thread isolated task execution engine with timeout enforcement.

### 2.3 Chronos Common
- Shared contracts, protobuf-generated stubs, domain enumerations, exception hierarchies, and utility constants. Zero business logic.

### 2.4 Chronos AI
- Isolated module boundary containing the AI Workflow Agent and AI Operations Agent. Optional and strictly segregated from core coordination mechanics.

---

## 3. Communication Protocols
- **Internal Cluster (Coordinator <-> Worker)**: gRPC over HTTP/2 with protobuf serialization.
- **Control Plane (Dashboard <-> Coordinator)**: REST / JSON over HTTP/1.1 with Server-Sent Events (SSE) for real-time telemetry.
- **Data Persistence**: JDBC pool (HikariCP) with PostgreSQL, Lettuce client with Redis.
