# Chronos Core

Distributed Task & Workflow Orchestration Platform

---

## 1. Project Overview

**Chronos Core** is a production-oriented distributed task and workflow orchestration platform designed for resilient, scalable execution of asynchronous jobs, recurring tasks, and complex workflow DAGs (Directed Acyclic Graphs). It is engineered from first principles with strong reliability guarantees, partition tolerance, and deep operational observability.

---

## 2. Problem Being Solved

Modern engineering platforms struggle with coordinating heterogeneous workloads at scale. Existing systems often suffer from:
- **Fragile Coordination & Split-Brain Risks**: Inadequate fencing and weak leader election mechanisms leading to conflicting task executions.
- **Resource Inefficiency**: Heavy thread exhaustion under high concurrency and poor worker utilization.
- **Opacity & Observability Gaps**: Difficulty in tracking complex workflow execution paths, failure cascades, and root-cause diagnostics across distributed clusters.
- **Loss of At-Least-Once Guarantees**: Stale task writes during network partitions or node failures.

Chronos Core addresses these challenges through lease-based leader election, fencing-token guarded execution, Java 21 Virtual Threads, durable state persistence in PostgreSQL, and real-time distributed cluster coordination via Redis.

---

## 3. Planned Architecture

The planned architecture decouples cluster orchestration from execution runners:

- **Coordinator Cluster**: Active-passive coordinator nodes with lease-based leader election in Redis. Responsible for task state machine transitions, priority queues, and DAG dependency resolution.
- **Worker Cluster**: Scalable execution nodes registering dynamically with the coordinator cluster, executing tasks with local resource sandboxing, and reporting heartbeats over bidirectional gRPC channels.
- **Storage Tier**:
  - **PostgreSQL**: Durable source of truth for task definitions, execution history, workflow versions, and audit logs.
  - **Redis**: Ephemeral distributed coordination, lease locks, worker heartbeat tracking, and fast state caching.
- **Control Plane**: A React + TypeScript engineering control plane for real-time cluster health inspection, DAG visualization, and operational triage.
- **AI Layer (Optional)**: Focused AI capabilities for workflow authoring assistance (AI Workflow Agent) and diagnostic log analysis (AI Operations Agent).

---

## 4. Technology Stack

- **Backend**: Java 21, Spring Boot 3.4.x, Virtual Threads (Project Loom)
- **RPC & Communication**: gRPC, Protocol Buffers
- **Coordination & Ephemeral State**: Redis
- **Persistence**: PostgreSQL, Flyway migrations
- **Frontend Control Plane**: React, TypeScript, Vite, Tailwind CSS
- **Observability**: Prometheus, Grafana, OpenTelemetry, Micrometer
- **Benchmarking & Testing**: k6, JUnit 5, Testcontainers

---

## 5. Repository Structure

```
chronos-core/
├── chronos-common/         # Shared domain contracts, models, and constants (no business logic)
├── chronos-coordinator/    # Coordinator service: election, scheduling, DAG orchestration
├── chronos-worker/         # Worker node service: execution runtime and heartbeat reporting
├── chronos-ai/             # Scoped AI service boundary (Workflow & Operations agents)
├── chronos-dashboard/      # React + TypeScript + Vite engineering control plane
├── infrastructure/         # Deployment definitions: Docker, Compose, Prometheus, Grafana
├── benchmark/              # Load and chaos benchmarks (k6 scripts)
├── docs/                   # Architectural blueprints, API contracts, failure mode analyses
├── .gitignore              # Project-wide VCS exclusions
├── AGENTS.md               # Persistent engineering and architectural rules
├── README.md               # Project documentation and specifications
└── pom.xml                 # Maven multi-module root POM
```

---

## 6. Development Status

> **Current Status**: **Phase 01: Project Foundation & Repository Setup**

The repository is currently in its initial foundation phase. The multi-module backend structure, Java 21 compiler configuration, Spring Boot service entry points, frontend scaffold, and project guidelines are established and verified. No business logic, scheduling algorithms, or distributed mechanisms have been implemented yet.

---

## 7. Planned Development Phases

1. **Phase 01: Project Foundation & Repository Setup** *(Current)*
2. **Phase 02: Core Domain Model & Shared Contracts**
3. **Phase 03: PostgreSQL Persistence & Schema Migrations**
4. **Phase 04: Redis Coordination & Lease-Based Leader Election**
5. **Phase 05: Task State Machine & Fencing Token Execution Engine**
6. **Phase 06: gRPC Communication & Worker Registration/Heartbeats**
7. **Phase 07: Priority Scheduler & Retry/Dead-Letter Queue Subsystems**
8. **Phase 08: Workflow DAG Execution Engine & Versioning**
9. **Phase 09: REST API Gateway & Security**
10. **Phase 10: Engineering Control Plane (React Dashboard)**
11. **Phase 11: AI Workflow & Operations Agents**
12. **Phase 12: Observability, Metrics & Production Hardening**
13. **Phase 13: Distributed Chaos Testing & k6 Benchmarks**
