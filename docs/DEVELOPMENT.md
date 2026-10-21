# Chronos Core - Developer Setup & Contribution Guide

## Status
- **Current Phase**: Phase 08 (Workflow & DAG Orchestration)

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
- `V5__create_task_leases.sql`: Distributed task execution lease management and worker fencing.
- `V6__create_workflow_orchestration.sql`: Workflow definitions, immutable versions, DAG nodes, edges, workflow executions, node execution states, and workflow audit log.

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

## 6. Distributed Scheduling & Task Distribution Configuration (Phase 06)

### 6.1 Scheduler & Hash Ring Environment Variables

The leader-aware scheduler and consistent hash distribution ring are configured via application properties:

| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `chronos.scheduler.enabled` | `CHRONOS_SCHEDULER_ENABLED` | `true` | Enable/disable the background leader scheduling loop. |
| `chronos.scheduler.poll-interval` | `CHRONOS_SCHEDULER_POLL_INTERVAL` | `100ms` | Polling delay between consecutive scheduler iterations. |
| `chronos.scheduler.batch-size` | `CHRONOS_SCHEDULER_BATCH_SIZE` | `50` | Maximum number of tasks selected per iteration. |
| `chronos.scheduler.task-lock-ttl` | `CHRONOS_SCHEDULER_TASK_LOCK_TTL` | `30s` | Redis distributed lock TTL for task dispatch exclusivity. |
| `chronos.scheduler.dispatch-timeout` | `CHRONOS_SCHEDULER_DISPATCH_TIMEOUT` | `5s` | Maximum duration to await worker gRPC dispatch response. |
| `chronos.scheduler.aging-threshold` | `CHRONOS_SCHEDULER_AGING_THRESHOLD` | `30s` | Threshold duration after which a task is considered starved. |
| `chronos.scheduler.starvation-ratio` | `CHRONOS_SCHEDULER_STARVATION_RATIO` | `0.2` | Fraction of batch capacity reserved for aged/starved tasks. |
| `chronos.distribution.virtual-nodes-per-worker` | `CHRONOS_DISTRIBUTION_VIRTUAL_NODES` | `128` | Number of virtual nodes hashed per worker on the ring. |

### 6.2 Running Scheduling Verification Tests

To run the complete suite of scheduling, consistent hashing, and multi-worker distribution tests:

```bash
# Run Consistent Hash Ring unit and distribution tests
mvn test -Dtest=ConsistentHashRingTest -pl chronos-coordinator

# Run Hash Ring empirical distribution benchmark
mvn test -Dtest=ConsistentHashRingBenchmarkTest -pl chronos-coordinator

# Run Worker Selector tests (capacity gating and fallback)
mvn test -Dtest=ConsistentHashWorkerSelectorTest -pl chronos-coordinator

# Run Scheduling Policy tests (priority and anti-starvation aging)
mvn test -Dtest=SchedulingPolicyTest -pl chronos-coordinator

# Run Leader-Aware Scheduler tests (fencing, locking, deadlines, DB transitions)
mvn test -Dtest=LeaderAwareTaskSchedulerTest -pl chronos-coordinator

# Run Multi-Coordinator Concurrency Simulation (distributed locking verification)
mvn test -Dtest=SchedulerConcurrencyIntegrationTest -pl chronos-coordinator

# Run Multi-Worker Distribution Integration (load distribution and capacity limits)
mvn test -Dtest=MultiWorkerDistributionIntegrationTest -pl chronos-coordinator
```

---

---

## 7. Fault Tolerance, Recovery & Lease Configuration (Phase 07)

### 7.1 Configuration Properties & Environment Variables

The recovery, retry, and execution lease subsystems are configured via application properties:

| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `chronos.lease.enabled` | `CHRONOS_LEASE_ENABLED` | `true` | Enable/disable execution lease tracking. |
| `chronos.lease.default-ttl` | `CHRONOS_LEASE_DEFAULT_TTL` | `30s` | Default TTL for an execution lease. |
| `chronos.lease.renewal-interval` | `CHRONOS_LEASE_RENEWAL_INTERVAL` | `10s` | Interval for renewing active execution leases. |
| `chronos.lease.safety-margin` | `CHRONOS_LEASE_SAFETY_MARGIN` | `5s` | Safety buffer before considering a lease expired. |
| `chronos.retry.enabled` | `CHRONOS_RETRY_ENABLED` | `true` | Enable/disable task failure retries. |
| `chronos.retry.base-delay` | `CHRONOS_RETRY_BASE_DELAY` | `1s` | Base backoff duration (`baseDelay * 2^(attempt-1)`). |
| `chronos.retry.max-delay` | `CHRONOS_RETRY_MAX_DELAY` | `60s` | Maximum backoff cap. |
| `chronos.retry.jitter` | `CHRONOS_RETRY_JITTER` | `FULL` | Jitter strategy (`NONE`, `FULL`, `DECORRELATED`). |
| `chronos.recovery.enabled` | `CHRONOS_RECOVERY_ENABLED` | `true` | Enable/disable background recovery scheduler. |
| `chronos.recovery.poll-interval` | `CHRONOS_RECOVERY_POLL_INTERVAL` | `1000ms`| Recovery sweep interval on the active leader. |
| `chronos.recovery.batch-size` | `CHRONOS_RECOVERY_BATCH_SIZE` | `50` | Maximum orphaned/expired tasks recovered per sweep. |

### 7.2 Flyway Schema Migration (V5)

Phase 07 adds schema migration `V5__create_task_leases.sql`:
- Creates durable execution lease table `chronos_task_leases`:
  - `task_id` (UUID, primary key)
  - `attempt_id` (UUID, not null)
  - `worker_id` (VARCHAR(128), not null)
  - `fence_token` (BIGINT, not null)
  - `acquired_at` (TIMESTAMPTZ, not null)
  - `expires_at` (TIMESTAMPTZ, not null)
- Adds index `idx_task_leases_expires` on `(expires_at)` for fast expired lease sweeps.
- Adds index `idx_task_leases_worker` on `(worker_id)` for worker eviction cleanup.

### 7.3 Running Fault Tolerance & Recovery Verification Tests

To run the complete suite of retry policy, backoff calculation, execution lease, recovery, and DLQ tests:

```bash
# Run Retry Policy tests (exponential progression, max cap, jitter bounds, classification)
mvn test -Dtest=DefaultRetryPolicyTest -pl chronos-coordinator

# Run Execution Lease Service tests (acquire, renew, release, expired search)
mvn test -Dtest=ExecutionLeaseServiceTest -pl chronos-coordinator

# Run Task Lease Repository Integration tests (H2/PostgreSQL schema operations)
mvn test -Dtest=JdbcTaskLeaseRepositoryIntegrationTest -pl chronos-coordinator

# Run Task Recovery Service tests (normal, retry backoff, max attempts DLQ, stale rejection, eviction)
mvn test -Dtest=TaskRecoveryServiceTest -pl chronos-coordinator

# Run Task Timeout Service tests (execution timeout detection, cooperative cancellation, DLQ)
mvn test -Dtest=TaskTimeoutServiceTest -pl chronos-coordinator

# Run DLQ Service tests (replay lifecycle, state reset, list, non-DLQ rejection)
mvn test -Dtest=DlqServiceTest -pl chronos-coordinator

# Run Recovery Concurrency Simulation (multi-threaded recovery conflict resolution)
mvn test -Dtest=RecoveryConcurrencyIntegrationTest -pl chronos-coordinator

# Run DAG Validation and Cycle Detection Tests (DFS 3-coloring, Kahn's topological sort)
mvn test -Dtest=WorkflowDagValidatorTest -pl chronos-common

# Run Workflow State Machine Tests (Workflow and Node status transitions)
mvn test -Dtest=WorkflowStateMachineTest,WorkflowNodeStateMachineTest -pl chronos-common

# Run Workflow Service Tests (draft, publication, immutability, deprecation)
mvn test -Dtest=WorkflowServiceTest -pl chronos-coordinator

# Run Workflow Execution Service Tests (fan-out, fan-in, failure propagation, cancellation)
mvn test -Dtest=WorkflowExecutionServiceTest -pl chronos-coordinator

# Run Workflow Concurrency Integration Tests (atomic fan-in activation, duplicate idempotency)
mvn test -Dtest=WorkflowConcurrencyIntegrationTest -pl chronos-coordinator
```

---

## 8. Module Responsibilities

| Module | Primary Responsibility |
| :--- | :--- |
| **`chronos-common`** | Core domain models (`Task`, `ExecutionAttempt`), enums (`TaskStatus`, `TaskPriority`), state machine, validation rules, shared DTOs, and Protobuf schemas. **Strictly zero business logic.** |
| **`chronos-coordinator`** | Active-passive leader lease election, task priority scheduling, DAG execution engine, persistence, cluster management, consistent hash distribution, execution leases, retry/recovery engine, and DLQ replay. |
| **`chronos-worker`** | Worker execution runtime, Java 21 virtual thread executor, task cancellation enforcement, and gRPC heartbeat reporting. |
| **`chronos-ai`** | Dedicated, isolated module boundary reserved for the AI Workflow and AI Operations agents. |
| **`chronos-dashboard`** | React + TypeScript + Vite engineering control plane for cluster visibility and operations. |

---

## 9. Frontend Build Instructions

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




