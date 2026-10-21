# Chronos Core - System Walkthrough & Operational Lifecycle

## Status
- **Current Phase**: Phase 01 (Foundation)

---

## 1. Task Lifecycle Overview

A task in Chronos Core follows a strictly verified finite state machine:

```
[ SUBMITTED ]
      │
      ▼
 [ PENDING ] ─────► [ SCHEDULED ] ─────► [ DISPATCHED ]
                                                │
                                                ▼
                                           [ RUNNING ]
                                          /     │     \
                                         /      │      \
                                        ▼       ▼       ▼
                             [ COMPLETED ]  [ FAILED ]  [ CANCELLED ]
                                                │
                                                ▼ (Retries Remaining?)
                                         [ RETRYING ] (Backoff + Jitter)
                                                │
                                                ▼ (Max Attempts Exceeded)
                                         [ DEAD_LETTER ]
```

---

## 2. DAG Execution Sequence

1. **Submission**: User submits a directed acyclic graph representing job dependencies.
2. **Cycle Detection**: Coordinator validates the DAG using Kahn's algorithm or DFS topological sort to guarantee acyclicity.
3. **Root Dispatch**: Nodes with zero in-degrees are immediately dispatched to available workers.
4. **Cascade Resolution**: When an upstream task completes, its output is recorded, dependent child in-degrees are decremented, and ready tasks are dispatched.
5. **Workflow Completion**: When all terminal nodes complete, the workflow transitions to `COMPLETED`.
