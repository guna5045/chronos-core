# Chronos Core - Workflow & DAG Orchestration Specification

## Status
- **Current Phase**: Phase 08 (Workflow & DAG Orchestration)
- **Document Version**: 0.8.0

---

## 1. Executive Overview

Phase 08 introduces the **Workflow and DAG Orchestration Engine** for Chronos Core.
Workflows allow engineers to compose dependent tasks into a Directed Acyclic Graph (DAG) with:
- Strict cycle detection and deterministic topological ordering
- Immutable workflow versioning and execution isolation
- Dependency-aware task activation (fan-out and fan-in)
- Concurrency-safe atomic node claiming (exactly-once task creation under concurrent dependency completion)
- Deterministic failure propagation and Dead-Letter Queue (DLQ) interaction
- Cooperative cancellation propagation to distributed workers
- Seamless reuse of existing Phase 06 scheduling and Phase 07 fault recovery mechanisms.

```
Workflow Definition
        │
        ▼
Workflow Version (Immutable)
        │
        ▼
Workflow Execution (Instance)
        │
        ▼
Dependency Engine
        │ (activates eligible nodes)
        ▼
Eligible Chronos Tasks (QUEUED)
        │
        ▼
Phase 06 Scheduler
        │
        ▼
Consistent Hashing & Capacity Tracking
        │
        ▼
gRPC Virtual-Thread Workers
        │
        ▼
Phase 07 Recovery & Retries
        │ (on logical success / terminal DLQ)
        ▼
Workflow State Update & Downstream Activation
```

> **Architectural Guarantee**: The workflow engine does **NOT** introduce a second scheduler, second queue, or second worker runtime. Every runnable workflow node is materialized as a standard `Task` in `TaskStatus.QUEUED` within PostgreSQL, scheduled by the existing Phase 06 leader-aware scheduler, executed on distributed workers via gRPC, and recovered by Phase 07 retry and lease supervision.

---

## 2. Workflow Domain Model

The workflow domain model is defined in `chronos-common` (`com.chronoscore.common.domain.workflow`):

### 2.1 Domain Entities

| Entity | Description | Mutability |
| :--- | :--- | :--- |
| `WorkflowDefinition` | Top-level workflow identity (e.g. `image-processing`, `daily-etl`). Tracks `workflowId`, `name`, `description`, `createdAt`, `updatedAt`. | Metadata editable |
| `WorkflowVersion` | Concrete graph definition of a workflow version (e.g. v1, v2). Encapsulates `nodes`, `edges`, `status` (`DRAFT`, `PUBLISHED`, `DEPRECATED`), and metadata. | **Immutable once published** |
| `WorkflowNodeDefinition` | Logical task specification within a version. Includes `nodeId`, `taskType`, `payload`, `priority`, `timeout`, `maxAttempts`. | Immutable within version |
| `WorkflowEdge` | Directed dependency edge from predecessor to successor: `(fromNodeId, toNodeId)`. | Immutable within version |
| `WorkflowExecution` | Runtime instance of a workflow version. Tracks `executionId`, `workflowId`, `versionId`, `status`, `tenantId`, timestamps, and optimistic locking `version`. | Mutable via state machine |
| `WorkflowExecutionNode` | State of an individual node in an execution instance. Tracks `executionId`, `nodeId`, associated Chronos `taskId`, `status`, execution timestamps, error details, and `version`. | Mutable via state machine |

---

## 3. Workflow State Machines

### 3.1 Workflow Lifecycle (`WorkflowStatus`)

```
               +-----------+
               |  PENDING  |
               +-----+-----+
                     |
                     v
               +-----------+
               |  RUNNING  +---------------+
               +-----+-----+               |
                     |                     |
         +-----------+-----------+         |
         |                       |         |
         v                       v         v
   +-----------+           +-----------+ +-----------+
   | SUCCEEDED |           |  FAILED   | | CANCELLED |
   | (Terminal)|           | (Terminal)| | (Terminal)|
   +-----------+           +-----------+ +-----------+
```

- `PENDING`: Initial state upon execution creation prior to root node activation.
- `RUNNING`: Workflow is actively executing tasks across the cluster.
- `SUCCEEDED`: All nodes defined in the workflow version have reached `SUCCEEDED` status.
- `FAILED`: A required node reached terminal failure (`FAILED` or `DLQ`), halting dependent branches.
- `CANCELLED`: The workflow was explicitly cancelled by an operator or timeout.

### 3.2 Workflow Node Lifecycle (`WorkflowNodeStatus`)

```
               +-----------+
               |  PENDING  |
               +--+--+--+--+
                  |  |  |
        +---------+  |  +---------+
        |            |            |
        v            v            v
  +-----------+ +-----------+ +-----------+
  |   READY   | |  SKIPPED  | | CANCELLED |
  +-----+-----+ | (Terminal)| | (Terminal)|
        |       +-----------+ +-----------+
        |
        +-----------------------+-----------------------+
        |                       |                       |
        v                       v                       v
  +-----------+           +-----------+           +-----------+
  |  RUNNING  |           | SUCCEEDED |           |  FAILED   |
  +-----+-----+           | (Terminal)|           | (Terminal)|
        |                 +-----------+           +-----------+
        +-----------+-----------+
        |           |           |
        v           v           v
  +-----------+ +-----------+ +-----------+
  | SUCCEEDED | |  FAILED   | | CANCELLED |
  | (Terminal)| | (Terminal)| | (Terminal)|
  +-----------+ +-----------+ +-----------+
```

- `PENDING`: Node is awaiting predecessor dependencies.
- `READY`: All predecessor dependencies are satisfied; task is queued for dispatch.
- `RUNNING`: Underlying Chronos task is actively executing on a worker.
- `SUCCEEDED`: Task completed successfully.
- `FAILED`: Task reached terminal failure (retries exhausted or unrecoverable error).
- `SKIPPED`: Upstream predecessor reached terminal failure; node will not execute.
- `CANCELLED`: Node was aborted during workflow cancellation.

### 3.3 Workflow Version Lifecycle (`WorkflowVersionStatus`)

```
   +---------+     publish()     +-----------+     deprecate()     +------------+
   |  DRAFT  | ────────────────> | PUBLISHED | ──────────────────> | DEPRECATED |
   +---------+                   +-----------+                     +------------+
```

- `DRAFT`: Editable version during definition authoring. Cannot be executed.
- `PUBLISHED`: Verified DAG version. **Strictly immutable**. Executable by new executions.
- `DEPRECATED`: Superseded version. Cannot initiate new executions; running executions continue unimpeded.

---

## 4. DAG Validation & Deterministic Algorithms

All workflow graph structures are validated by `WorkflowDagValidator` (`chronos-common`) prior to publishing.

### 4.1 Cycle Detection (DFS 3-Coloring)
The validator implements depth-first search 3-coloring:
- `WHITE` (unvisited): Node has not been encountered.
- `GRAY` (visiting): Node is currently in the active DFS recursion stack.
- `BLACK` (visited): Node and all its descendants have been completely explored.

If a DFS traversal encounters a node currently marked `GRAY`, a directed back-edge exists, proving a cycle. The validator rejects the graph with a clear diagnostic message identifying the cycle.

### 4.2 Deterministic Topological Ordering (Kahn's Algorithm)
When ordering nodes for execution scheduling or visualization, Chronos Core employs Kahn's algorithm augmented with **lexicographical tie-breaking**:
1. In-degrees are computed for every node from the edge list.
2. All nodes with in-degree 0 (root nodes) are added to a `PriorityQueue` sorted lexicographically by `nodeId`.
3. Nodes are extracted deterministically; for each neighbor, in-degree is decremented. When in-degree reaches 0, the neighbor is inserted into the priority queue.
4. If the number of processed nodes is less than the total node count, a cycle exists.

### 4.3 Validation Rules
- **Non-Empty**: Workflow must contain at least 1 node.
- **Node ID Uniqueness**: No duplicate node identifiers within the version.
- **Valid Edge References**: Every `fromNodeId` and `toNodeId` must reference a declared node.
- **No Self-Dependencies**: Direct loops `A -> A` are rejected.
- **No Multi-Node Cycles**: Circular chains (e.g. `A -> B -> C -> A`) are rejected.
- **At Least One Root Node**: At least one node must have in-degree 0.
- **Safe Graph Limits**: Defaults enforce maximum 500 nodes and 2,000 edges per workflow version.

---

## 5. Execution Model & Dependency Activation

### 5.1 Root Node Activation
When `WorkflowExecutionService.startExecution` is invoked:
1. Validates that the targeted workflow version is `PUBLISHED`.
2. Persists the `WorkflowExecution` record in `WorkflowStatus.RUNNING`.
3. Persists initial `WorkflowExecutionNode` records for all nodes in `WorkflowNodeStatus.PENDING`.
4. Identifies all root nodes (in-degree 0) and activates them immediately.

### 5.2 Dependency Policy
Chronos Core enforces the standard dependency rule:
> **A downstream node becomes eligible for execution if and only if ALL its declared predecessor nodes have reached `WorkflowNodeStatus.SUCCEEDED`.**

### 5.3 Fan-Out / Fan-In Execution
Consider a diamond DAG:
```
       A
      / \
     B   C
      \ /
       D
```
1. **Root Activation**: `A` is activated and queued as a Chronos task.
2. **Fan-Out**: When `A` completes with `SUCCEEDED`:
   - Both `B` and `C` have their predecessor requirements satisfied.
   - `B` and `C` are concurrently activated and submitted to the Phase 06 scheduler.
   - `D` remains `PENDING` because predecessors `B` and `C` are not yet `SUCCEEDED`.
3. **Partial Completion**: When `B` completes with `SUCCEEDED`:
   - Predecessors for `D` are evaluated: `B=SUCCEEDED`, but `C=RUNNING`.
   - `D` remains `PENDING`.
4. **Fan-In Activation**: When `C` completes with `SUCCEEDED`:
   - Predecessors for `D` are re-evaluated: both `B` and `C` are `SUCCEEDED`.
   - `D` is claimed and activated as a queued Chronos task.
5. **Completion**: When `D` completes, all 4 nodes are `SUCCEEDED`, and the workflow transitions to `WorkflowStatus.SUCCEEDED`.

---

## 6. Concurrency Protection & Atomic Activation

### 6.1 The Concurrent Fan-In Race Problem
When independent nodes (such as `B` and `C`) finish concurrently on different worker threads, both threads may concurrently evaluate downstream node `D`:
- Thread 1 checks if all predecessors of `D` are `SUCCEEDED` &rarr; returns `true`.
- Thread 2 checks if all predecessors of `D` are `SUCCEEDED` &rarr; returns `true`.

Without atomic protection, both threads would instantiate a task for `D`, resulting in duplicate execution.

### 6.2 Atomic Compare-and-Swap (CAS) Activation
Chronos Core prevents duplicate activation at the database row level via atomic conditional updates:
```sql
UPDATE chronos_workflow_execution_nodes
SET status = 'READY',
    started_at = :startedAt,
    version = version + 1
WHERE execution_id = :executionId
  AND node_id = :nodeId
  AND status = 'PENDING';
```

- **Single Winner**: PostgreSQL row-level locks ensure that exactly one transaction updates the row from `PENDING` to `READY` (returning `rowsAffected = 1`).
- **Loser Aborts**: The competing transaction sees `rowsAffected = 0` (status is no longer `PENDING`) and immediately skips task submission.
- **Idempotency Key**: Each generated task carries an idempotency key composed of `executionId:nodeId`, providing secondary protection at the `chronos_tasks` unique constraint level.

---

## 7. Fault Tolerance & Recovery Integration

### 7.1 Retries vs Logical Node Failure
A distinction is strictly maintained between **transient task attempt failures** and **terminal workflow node failure**:
- If a task attempt fails transiently (e.g. network glitch or worker crash), Phase 07 recovery applies `RetryPolicy` with exponential backoff and jitter.
- The task is rescheduled (`QUEUED` with future `scheduledAt`).
- The logical workflow node remains `RUNNING` or `READY`.
- **Workflow dependencies are NOT triggered** by intermediate attempt failures.

### 7.2 DLQ & Failure Propagation
When a task exhausts all retry attempts (`currentAttempt >= maxAttempts`):
1. Phase 07 recovery transitions the task to `TaskStatus.DLQ`.
2. The coordinator forwards the terminal failure event to `WorkflowExecutionService.onTaskCompleted(taskId, false, errorCode, errorMessage)`.
3. The failing node is transitioned to `WorkflowNodeStatus.FAILED`.
4. **Failure Propagation**: A breadth-first search finds all reachable downstream descendant nodes. All non-terminal descendants are transitioned to `WorkflowNodeStatus.SKIPPED`.
5. The `WorkflowExecution` is transitioned to `WorkflowStatus.FAILED`.
6. Audit records and outbox events are appended atomically.

---

## 8. Cancellation Semantics

When an operator or timeout cancels a workflow execution (`cancelExecution`):
1. `WorkflowExecution` transitions `RUNNING -> CANCELLED`.
2. All non-terminal execution nodes are transitioned:
   - `PENDING` nodes &rarr; `CANCELLED`
   - `READY` nodes &rarr; `CANCELLED`
   - `RUNNING` nodes &rarr; `CANCELLED`
3. For nodes actively executing on workers (`RUNNING`), the coordinator looks up their `TaskExecutionLease`, identifies the assigned worker, and issues a cooperative cancellation request via gRPC (`cancelTask`), terminating the worker's virtual thread.
4. Already `SUCCEEDED` or `FAILED` nodes are preserved without modification.

---

## 9. Workflow Versioning & Execution Isolation

### 9.1 Immutability Guarantee
Once a workflow version transitions to `PUBLISHED`:
- Nodes cannot be added, removed, or modified.
- Edges cannot be added, removed, or modified.
- To change a workflow, engineers must draft and publish a new version (e.g. v2).

### 9.2 Execution Version Pinning
When an execution starts, it records its `versionId`.
- Even if v2 or v3 is published while execution #123 is in-flight, execution #123 remains strictly bound to its original version definition.
- Task completion events always evaluate edges and node definitions belonging to the execution's pinned `versionId`.

### 9.3 Execution Isolation
Executions are completely isolated. Two concurrent executions (Execution A and Execution B) of the same workflow version maintain independent node execution rows in `chronos_workflow_execution_nodes`. Completion of a node in Execution A never affects Execution B.

---

## 10. Persistence Schema (`V6__create_workflow_orchestration.sql`)

### 10.1 Schema Structure
The workflow persistence layer utilizes 7 normalized tables:

1. `chronos_workflows`: Top-level workflow registry (primary key `workflow_id`, unique `name`).
2. `chronos_workflow_versions`: Workflow version definitions (primary key `version_id`, unique `(workflow_id, version)`).
3. `chronos_workflow_version_nodes`: Node definitions per version (primary key `(version_id, node_id)`).
4. `chronos_workflow_version_edges`: Directed dependency edges (primary key `(version_id, from_node_id, to_node_id)`).
5. `chronos_workflow_executions`: Execution instances (primary key `execution_id`, foreign keys to workflow and version).
6. `chronos_workflow_execution_nodes`: Node execution states per instance (primary key `(execution_id, node_id)`, foreign key `task_id` referencing `chronos_tasks(task_id)`).
7. `chronos_workflow_audit_log`: Audit trail for all workflow lifecycle transitions.

### 10.2 Database Integrity Constraints
- Foreign keys with `ON DELETE CASCADE` for version definitions and nodes.
- Foreign key `task_id` on `chronos_workflow_execution_nodes` referencing `chronos_tasks(task_id)` with `ON DELETE SET NULL`.
- Unique constraint `uq_wf_version_number` preventing duplicate version numbers for the same workflow.
- Optimistic locking `version` column on `chronos_workflow_executions` and `chronos_workflow_execution_nodes`.

---

## 11. Delivery Guarantee Summary

Chronos Core guarantees:
> **At-least-once task delivery with dependency-aware DAG orchestration, atomic exactly-once task activation, deterministic failure propagation, execution version pinning, and stale execution fencing.**

Chronos Core does **NOT** claim exactly-once end-to-end execution.
