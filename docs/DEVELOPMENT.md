# Chronos Core - Developer Setup & Contribution Guide

## Status
- **Current Phase**: Phase 02 (Core Domain Model & Shared Contracts)

---

## 1. Prerequisites

- **Java**: OpenJDK 21 (LTS)
- **Maven**: Apache Maven 3.9+
- **Node.js**: v20+ LTS / v24+
- **Git**: 2.40+

---

## 2. Backend Build & Testing

### 2.1 Complete Build with Automated Tests
To run full dependency resolution, Protocol Buffer code generation, compilation, and test suites across all Maven modules:

```bash
mvn clean test
```

### 2.2 Protobuf & gRPC Code Generation
Protobuf definitions are located in `chronos-common/src/main/proto/chronos_task_execution.proto`.
During the Maven `generate-sources` lifecycle phase:
1. Standard Google Protobuf descriptor definitions are extracted.
2. Protoc and the gRPC Java plugin generate serialization models and service stubs into:
   - `chronos-common/target/generated-sources/protobuf/java`
   - `chronos-common/target/generated-sources/protobuf/grpc-java`
3. Sources are automatically registered into the compiler classpath.

To explicitly trigger code generation for `chronos-common`:

```bash
mvn generate-sources -f chronos-common/pom.xml
```

---

## 3. Module Responsibilities

| Module | Primary Responsibility |
| :--- | :--- |
| **`chronos-common`** | Core domain models (`Task`, `ExecutionAttempt`), enums (`TaskStatus`, `TaskPriority`), state machine, validation rules, shared DTOs, and Protobuf schemas. **Strictly zero business logic.** |
| **`chronos-coordinator`** | Active-passive leader lease election, task priority scheduling, DAG execution engine, and cluster management. |
| **`chronos-worker`** | Worker execution runtime, task cancellation enforcement, local resource monitoring, and gRPC heartbeat reporting. |
| **`chronos-ai`** | Dedicated, isolated module boundary reserved for the AI Workflow and AI Operations agents. |
| **`chronos-dashboard`** | React + TypeScript + Vite engineering control plane for cluster visibility and operations. |

---

## 4. Frontend Build Instructions

```bash
cd chronos-dashboard
npm install
npm run build
```
To run the local Vite development server:
```bash
npm run dev
```
Accessible at: `http://localhost:5173`.
