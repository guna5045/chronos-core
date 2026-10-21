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

## 3. Database Infrastructure & Persistence (Phase 03)

### 3.1 Local PostgreSQL via Docker Compose
To run a local PostgreSQL 16 instance for development:

```bash
cd infrastructure/compose
docker compose up -d postgres
```

To inspect container logs:
```bash
docker compose logs -f postgres
```

To shut down the database preserving durable data volumes:
```bash
docker compose down
```

### 3.2 Database Environment Variables
The coordinator reads its datasource configuration through standard environment variables with development defaults:

| Variable | Description | Development Default |
| :--- | :--- | :--- |
| `CHRONOS_DB_URL` | JDBC connection URL | `jdbc:postgresql://localhost:5432/chronos_core` |
| `CHRONOS_DB_USERNAME` | Database user | `chronos_user` |
| `CHRONOS_DB_PASSWORD` | Database password | `chronos_dev_pass` |
| `CHRONOS_DB_NAME` | Database catalog name | `chronos_core` |
| `CHRONOS_DB_PORT` | Local host port mapping | `5432` |
| `CHRONOS_DB_POOL_MAX` | HikariCP max pool size | `10` |
| `CHRONOS_DB_POOL_MIN_IDLE` | HikariCP min idle connections | `2` |

> **Security Note**: Never commit production credentials to source control or `.env` files. Always inject credentials through runtime environment variables.

### 3.3 Flyway Database Migrations
Migrations are managed by Flyway and applied automatically on coordinator startup.
Migration scripts are located at:
`chronos-coordinator/src/main/resources/db/migration/`
- `V1__create_tasks.sql`: Task table, check constraints, partial idempotency index, queue index.
- `V2__create_execution_attempts.sql`: Physical execution attempt history and foreign key constraints.
- `V3__create_task_audit_log.sql`: Append-only task state transition audit log.
- `V4__create_outbox_events.sql`: Transactional outbox event staging table.

### 3.4 Integration Testing & Testcontainers
- When Docker is available, `PostgreSqlTestcontainersIT` verifies Flyway migrations and persistence operations against real PostgreSQL 16 in an isolated container.
- For local or CI environments without Docker daemon access, all persistence repositories, optimistic concurrency tests, and transaction rollback tests execute using the integrated test profile (`application-test.yml`).


---

## 4. Module Responsibilities

| Module | Primary Responsibility |
| :--- | :--- |
| **`chronos-common`** | Core domain models (`Task`, `ExecutionAttempt`), enums (`TaskStatus`, `TaskPriority`), state machine, validation rules, shared DTOs, and Protobuf schemas. **Strictly zero business logic.** |
| **`chronos-coordinator`** | Active-passive leader lease election, task priority scheduling, DAG execution engine, persistence, and cluster management. |
| **`chronos-worker`** | Worker execution runtime, task cancellation enforcement, local resource monitoring, and gRPC heartbeat reporting. |
| **`chronos-ai`** | Dedicated, isolated module boundary reserved for the AI Workflow and AI Operations agents. |
| **`chronos-dashboard`** | React + TypeScript + Vite engineering control plane for cluster visibility and operations. |

---

## 5. Frontend Build Instructions

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

