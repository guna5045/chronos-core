# Chronos Core - Database & Persistence Specification

## Status
- **Current Phase**: Phase 03 (Persistence & Durable State)
- **Target Engine**: PostgreSQL 16+
- **Schema Management**: Flyway Versioned Migrations

---

## 1. Architectural Philosophy: PostgreSQL as Durable Source of Truth

Chronos Core establishes **PostgreSQL as the durable, authoritative source of truth** for all task state, execution history, lifecycle audit trails, and staged transactional outbox events.

Key Architectural Guarantees:
- **Zero Ephemeral State Authority**: Task state and lifecycle progression live authoritatively in PostgreSQL. Later coordination systems (e.g. Redis leases, distributed locks, fencing tokens) will coordinate execution and scheduling, but task state authority remains strictly anchored in PostgreSQL.
- **Explicit SQL & Fine-Grained Control**: The coordinator persistence tier uses explicit SQL via Spring `NamedParameterJdbcTemplate` rather than heavyweight ORM layers. This provides transparent control over transactions, connection pools, row locks (`FOR UPDATE`), partial indexes, and optimistic version checks.
- **Independent Domain Model**: Pure domain contracts in `chronos-common` (`Task`, `ExecutionAttempt`, `TaskStateMachine`) remain completely decoupled from Spring and PostgreSQL libraries.

---

## 2. Flyway Schema Migrations

Flyway migrations are version-controlled in `chronos-coordinator/src/main/resources/db/migration/`:

| Version | Migration Script | Purpose |
| :--- | :--- | :--- |
| `V1` | `V1__create_tasks.sql` | Primary durable task table, check constraints, operational indexes, partial idempotency index, queue index. |
| `V2` | `V2__create_execution_attempts.sql` | Execution attempts history table, foreign keys, unique attempt constraint, worker indexes. |
| `V3` | `V3__create_task_audit_log.sql` | Strictly append-only task state transition audit log. |
| `V4` | `V4__create_outbox_events.sql` | Transactional outbox table for reliable at-least-once event publication. |

---

## 3. Detailed Schema Specification

### 3.1 `chronos_tasks` Table

Authoritative table representing logical task instances.

```sql
CREATE TABLE chronos_tasks (
    task_id          UUID                     NOT NULL,
    task_type        VARCHAR(255)             NOT NULL,
    payload          TEXT                     NOT NULL,
    priority         VARCHAR(50)              NOT NULL,
    status           VARCHAR(50)              NOT NULL,
    tenant_id        VARCHAR(255)             NOT NULL,
    idempotency_key  VARCHAR(255),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    scheduled_at     TIMESTAMP WITH TIME ZONE,
    deadline         TIMESTAMP WITH TIME ZONE,
    timeout_ms       BIGINT,
    max_attempts     INTEGER                  NOT NULL,
    current_attempt  INTEGER                  NOT NULL DEFAULT 0,
    version          BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_chronos_tasks PRIMARY KEY (task_id),
    CONSTRAINT chk_tasks_max_attempts CHECK (max_attempts > 0),
    CONSTRAINT chk_tasks_current_attempt CHECK (current_attempt >= 0),
    CONSTRAINT chk_tasks_version CHECK (version >= 0),
    CONSTRAINT chk_tasks_timeout CHECK (timeout_ms IS NULL OR timeout_ms >= 0)
);
```

#### Idempotency Strategy
Enforced via a partial unique index:
```sql
CREATE UNIQUE INDEX uq_chronos_tasks_tenant_idempotency
    ON chronos_tasks (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```
- Deduplication is tenant-scoped: tenant A and tenant B can use the same key independently.
- Multiple tasks submitted with `NULL` idempotency key for the same tenant are freely permitted without conflict.
- Retries with the same non-null key within a tenant are safely rejected with `DuplicateTaskException`.

#### Task Table Indexes
- `idx_chronos_tasks_status` (`status`): Accelerated status filtering and operational lifecycle metric collection.
- `idx_chronos_tasks_priority` (`priority`): Priority distribution queries.
- `idx_chronos_tasks_scheduled_at` (`scheduled_at`): Time-based future dispatch queries.
- `idx_chronos_tasks_tenant_id` (`tenant_id`): Multi-tenant isolation and tenant-level quota enforcement.
- `idx_chronos_tasks_created_at` (`created_at`): Time-series queries and historical audit windows.
- `idx_chronos_tasks_deadline` (`deadline`): Deadline breach monitoring and alerting.
- **`idx_chronos_tasks_queued`** (`priority, scheduled_at, created_at` WHERE `status = 'QUEUED'`): Specialized partial index designed for high-throughput scheduler queue acquisition (`SELECT ... FOR UPDATE SKIP LOCKED` in Phase 07).

---

### 3.2 `chronos_execution_attempts` Table

Tracks physical execution attempts on worker nodes across retries.

```sql
CREATE TABLE chronos_execution_attempts (
    attempt_id       UUID                     NOT NULL,
    task_id          UUID                     NOT NULL,
    worker_id        VARCHAR(255)             NOT NULL,
    attempt_number   INTEGER                  NOT NULL,
    status           VARCHAR(50)              NOT NULL,
    started_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at     TIMESTAMP WITH TIME ZONE,
    error_code       VARCHAR(100),
    error_message    TEXT,

    CONSTRAINT pk_chronos_execution_attempts PRIMARY KEY (attempt_id),
    CONSTRAINT fk_execution_attempts_task FOREIGN KEY (task_id)
        REFERENCES chronos_tasks (task_id) ON DELETE CASCADE,
    CONSTRAINT uq_execution_attempts_task_attempt UNIQUE (task_id, attempt_number),
    CONSTRAINT chk_execution_attempts_attempt_number CHECK (attempt_number >= 1)
);
```

#### Guarantees
- History Preservation: Execution attempts are never deleted when a task is retried. The full progression from attempt 1 to max attempts is preserved for debugging and AI Operations triage.
- Sequence Integrity: `uq_execution_attempts_task_attempt` prevents accidental duplicate attempt numbers for the same task.

---

### 3.3 `chronos_task_audit_log` Table

Immutable, append-only ledger of every task state mutation.

```sql
CREATE TABLE chronos_task_audit_log (
    audit_id        UUID                     NOT NULL,
    task_id         UUID                     NOT NULL,
    from_status     VARCHAR(50),
    to_status       VARCHAR(50)              NOT NULL,
    attempt_number  INTEGER                  NOT NULL DEFAULT 0,
    reason          TEXT,
    metadata        TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_chronos_task_audit_log PRIMARY KEY (audit_id),
    CONSTRAINT fk_task_audit_task FOREIGN KEY (task_id)
        REFERENCES chronos_tasks (task_id) ON DELETE CASCADE
);
```

- Append-Only Rule: Rows are strictly inserted; never updated or deleted by application code.
- Indexes on `task_id` and `created_at` ensure rapid linear reconstruction of a task's full lifecycle history.

---

### 3.4 `chronos_outbox_events` Table

Durable transactional outbox guaranteeing at-least-once event publication without dual-write consistency hazards.

```sql
CREATE TABLE chronos_outbox_events (
    event_id        UUID                     NOT NULL,
    aggregate_type  VARCHAR(100)             NOT NULL,
    aggregate_id    VARCHAR(255)             NOT NULL,
    event_type      VARCHAR(100)             NOT NULL,
    payload         TEXT                     NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at    TIMESTAMP WITH TIME ZONE,
    attempt_count   INTEGER                  NOT NULL DEFAULT 0,

    CONSTRAINT pk_chronos_outbox_events PRIMARY KEY (event_id),
    CONSTRAINT chk_outbox_attempt_count CHECK (attempt_count >= 0)
);
```

- Partial Index: `idx_chronos_outbox_pending` on `(created_at) WHERE published_at IS NULL` allows future outbox sweepers to poll exclusively for pending events without scanning published records.

---

## 4. Concurrency & Locking Semantics

### 4.1 Optimistic Concurrency Control
All task updates use optimistic concurrency version checks:
```sql
UPDATE chronos_tasks
SET
    status = :status,
    current_attempt = :currentAttempt,
    version = version + 1
WHERE task_id = :taskId
  AND version = :version;
```
If 0 rows are updated:
- The repository checks if the task exists.
- If it exists, an `OptimisticLockingException` is thrown, signaling that a concurrent update advanced the version first.
- The rejected update cannot overwrite newer state, preventing lost updates.

### 4.2 Pessimistic Row Locking Preparation
The repository provides `findByIdForUpdate(taskId)` which executes:
```sql
SELECT ... FROM chronos_tasks WHERE task_id = ? FOR UPDATE;
```
This acquires a transaction-scoped row lock on PostgreSQL, preparing the coordinator for serialized state modifications and concurrent queue polling.

---

## 5. Architectural Decisions

1. **`TEXT` Payload Representation**:
   Stored as `TEXT NOT NULL` rather than dialect-specific `JSONB` to preserve pure domain independence, allow arbitrary structured payloads (JSON, text, XML), and prevent JDBC driver class coupling.
2. **Spring JDBC vs ORM**:
   Spring `NamedParameterJdbcTemplate` provides total transparency over query execution, row locks, optimistic concurrency checks, and connection lifecycle without JPA proxy overhead or hidden flush behaviors.
3. **Task Version vs Distributed Fencing Token**:
   `Task.version` is strictly the database/domain row version. Later distributed coordination (Phase 04) introduces separate epoch fencing tokens.
