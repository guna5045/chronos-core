# Chronos Core - Developer Setup & Contribution Guide

## Status
- **Current Phase**: Phase 05 (Worker Cluster & gRPC Communication)

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

## 4. Redis Infrastructure & Distributed Coordination (Phase 04)

### 4.1 Local Redis via Docker Compose
To run Redis 7.2 alongside PostgreSQL for local coordination development:

```bash
cd infrastructure/compose
docker compose up -d redis
```

To run both PostgreSQL and Redis together:
```bash
docker compose up -d
```

To inspect Redis logs or healthcheck:
```bash
docker compose logs -f redis
docker compose ps
```

### 4.2 Coordination Environment Variables
The coordinator reads its coordination and Redis configuration through standard environment variables with development defaults:

| Variable | Description | Development Default |
| :--- | :--- | :--- |
| `CHRONOS_COORDINATION_ENABLED` | Enable/disable distributed leader loop | `true` |
| `CHRONOS_COORDINATOR_ID` | Coordinator instance identifier | Hostname / generated |
| `CHRONOS_LEADER_LEASE_DURATION` | Redis leader lock TTL | `5s` |
| `CHRONOS_LEADER_RENEWAL_INTERVAL` | Scheduled lease renewal tick | `1500ms` |
| `CHRONOS_TASK_LOCK_TTL` | Distributed task lock TTL | `30s` |
| `CHRONOS_REDIS_HOST` | Redis server hostname | `localhost` |
| `CHRONOS_REDIS_PORT` | Redis server port | `6379` |

### 4.3 Redis Integration Testing
- `RedisTestcontainersIT` verifies real Redis atomic lease election, epoch incrementation, Lua renewals, Lua safe releases, and distributed task lock semantics against an isolated Redis 7.2 container.
- When Docker is not available in the local environment, `DockerClientFactory.instance().isDockerAvailable()` skips container startup cleanly while unit and concurrency tests (`LeaderElectionConcurrencyTest`, `DistributedLockConcurrencyTest`) execute in-memory with zero Docker dependency.

---

## 5. Worker Cluster Development & gRPC Configuration (Phase 05)

### 5.1 Running Coordinator & Worker Locally
To start the Coordinator gRPC server and a Worker node locally:

1. **Start Infrastructure**:
   ```bash
   cd infrastructure/compose
   docker compose up -d
   ```

2. **Start Coordinator**:
   ```bash
   mvn spring-boot:run -pl chronos-coordinator
   ```
   The Coordinator binds Netty gRPC server to `localhost:9090`.

3. **Start Worker**:
   ```bash
   mvn spring-boot:run -pl chronos-worker
   ```
   The Worker registers with the Coordinator, starts its Netty gRPC server on `localhost:9091`, and begins streaming heartbeats every 1,000ms.

### 5.2 Worker Environment Variables

| Variable | Description | Development Default |
| :--- | :--- | :--- |
| `CHRONOS_WORKER_ENABLED` | Enable/disable worker runtime | `true` |
| `CHRONOS_WORKER_ID` | Unique worker instance identifier | `worker-local-01` |
| `CHRONOS_WORKER_PORT` | Worker gRPC listening port | `9091` |
| `CHRONOS_WORKER_MAX_CONCURRENCY` | Maximum concurrent virtual threads | `16` |
| `CHRONOS_COORDINATOR_HOST` | Coordinator hostname | `localhost` |
| `CHRONOS_COORDINATOR_PORT` | Coordinator gRPC listening port | `9090` |
| `CHRONOS_HEARTBEAT_INTERVAL_MS`| Streaming heartbeat interval | `1000` |

---

## 6. Module Responsibilities

| Module | Primary Responsibility |
| :--- | :--- |
| **`chronos-common`** | Core domain models (`Task`, `ExecutionAttempt`), enums (`TaskStatus`, `TaskPriority`), state machine, validation rules, shared DTOs, and Protobuf schemas. **Strictly zero business logic.** |
| **`chronos-coordinator`** | Active-passive leader lease election, task priority scheduling, DAG execution engine, persistence, cluster management, and in-memory worker registry. |
| **`chronos-worker`** | Worker execution runtime, Java 21 virtual thread executor, task cancellation enforcement, and gRPC heartbeat reporting. |
| **`chronos-ai`** | Dedicated, isolated module boundary reserved for the AI Workflow and AI Operations agents. |
| **`chronos-dashboard`** | React + TypeScript + Vite engineering control plane for cluster visibility and operations. |

---

## 7. Frontend Build Instructions

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



