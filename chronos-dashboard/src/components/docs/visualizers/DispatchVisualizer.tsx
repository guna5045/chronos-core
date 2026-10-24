import React, { useState } from 'react';
import { Play, RotateCcw } from 'lucide-react';

export const DispatchVisualizer: React.FC = () => {
  const [step, setStep] = useState(0);

  const steps = [
    {
      title: '1. REST Ingestion',
      component: 'Client -> Coordinator (HTTP :8080)',
      detail: 'Client issues POST /api/v1/tasks with JSON payload and tenant token. Coordinator verifies admission control quotas and idempotency key.',
      activeBox: 'client_coordinator',
    },
    {
      title: '2. Durable DB Commit',
      component: 'Coordinator -> PostgreSQL (:5432)',
      detail: 'Task entity is committed to chronos_tasks table with status QUEUED, version 0, and current_attempt 0 within an ACID transaction.',
      activeBox: 'postgres',
    },
    {
      title: '3. Queue Polling (SKIP LOCKED)',
      component: 'LeaderAwareTaskScheduler',
      detail: 'Leader coordinator polls database using SELECT ... FOR UPDATE SKIP LOCKED. Fetches batch of 50 tasks ordered by priority and schedule time.',
      activeBox: 'scheduler',
    },
    {
      title: '4. Consistent Hash Worker Selection',
      component: 'ConsistentHashRouter',
      detail: 'Task ID is hashed against the 384 virtual nodes on the ring. Resolves clockwise to target Worker 2 (active concurrency: 3/8).',
      activeBox: 'ring',
    },
    {
      title: '5. gRPC Protobuf Dispatch',
      component: 'Coordinator -> Worker 2 (gRPC :9092)',
      detail: 'TaskAssignment Protobuf message is streamed with active leader epoch and task payload. Task status transitions to DISPATCHED.',
      activeBox: 'grpc',
    },
    {
      title: '6. Virtual Thread Execution',
      component: 'Worker 2 Runtime',
      detail: 'Worker allocates a virtual thread slot from its semaphore. Spawns Java 21 user thread and executes user computation.',
      activeBox: 'worker',
    },
    {
      title: '7. Completion & Outbox Commit',
      component: 'Worker -> Coordinator -> PostgreSQL',
      detail: 'Worker reports EXECUTION_STATUS_SUCCEEDED. Coordinator updates task to SUCCEEDED, saves execution attempt row, and logs outbox event.',
      activeBox: 'complete',
    },
  ];

  const current = steps[step];

  return (
    <div
      style={{
        backgroundColor: 'var(--bg-elevated)',
        border: '1px solid var(--border-default)',
        borderRadius: '0.5rem',
        padding: '1.25rem',
        display: 'flex',
        flexDirection: 'column',
        gap: '1rem',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '0.75rem' }}>
        <div>
          <span style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase' }}>
            Distributed Task Dispatch &amp; Execution Pipeline
          </span>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
            Client REST • PostgreSQL SKIP LOCKED • Consistent Hash • gRPC • Virtual Threads
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
          <button
            onClick={() => setStep(Math.min(steps.length - 1, step + 1))}
            disabled={step === steps.length - 1}
            className="btn btn-secondary btn-sm"
          >
            <Play size={13} />
            <span>Next Phase</span>
          </button>
          <button onClick={() => setStep(0)} className="btn btn-secondary btn-sm">
            <RotateCcw size={13} />
            <span>Reset</span>
          </button>
        </div>
      </div>

      {/* Visual Pipeline Boxes */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))',
          gap: '0.5rem',
          padding: '0.75rem',
          backgroundColor: 'var(--bg-surface)',
          borderRadius: '0.375rem',
          border: '1px solid var(--border-subtle)',
        }}
      >
        <div
          style={{
            padding: '0.6rem',
            borderRadius: '0.25rem',
            border: `1px solid ${step === 0 ? 'var(--accent-cyan)' : 'var(--border-default)'}`,
            backgroundColor: step === 0 ? 'rgba(56, 189, 248, 0.1)' : 'var(--bg-elevated)',
            textAlign: 'center',
          }}
        >
          <div style={{ fontSize: '0.75rem', fontWeight: 600 }}>1. Client API</div>
          <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)' }}>HTTP REST</div>
        </div>

        <div
          style={{
            padding: '0.6rem',
            borderRadius: '0.25rem',
            border: `1px solid ${step === 1 || step === 2 ? 'var(--accent-cyan)' : 'var(--border-default)'}`,
            backgroundColor: step === 1 || step === 2 ? 'rgba(56, 189, 248, 0.1)' : 'var(--bg-elevated)',
            textAlign: 'center',
          }}
        >
          <div style={{ fontSize: '0.75rem', fontWeight: 600 }}>2. PostgreSQL</div>
          <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)' }}>SKIP LOCKED</div>
        </div>

        <div
          style={{
            padding: '0.6rem',
            borderRadius: '0.25rem',
            border: `1px solid ${step === 3 || step === 4 ? 'var(--accent-cyan)' : 'var(--border-default)'}`,
            backgroundColor: step === 3 || step === 4 ? 'rgba(56, 189, 248, 0.1)' : 'var(--bg-elevated)',
            textAlign: 'center',
          }}
        >
          <div style={{ fontSize: '0.75rem', fontWeight: 600 }}>3. Scheduler</div>
          <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)' }}>Hash Ring</div>
        </div>

        <div
          style={{
            padding: '0.6rem',
            borderRadius: '0.25rem',
            border: `1px solid ${step === 5 || step === 6 ? 'var(--accent-emerald)' : 'var(--border-default)'}`,
            backgroundColor: step === 5 || step === 6 ? 'rgba(16, 185, 129, 0.1)' : 'var(--bg-elevated)',
            textAlign: 'center',
          }}
        >
          <div style={{ fontSize: '0.75rem', fontWeight: 600 }}>4. Worker 2</div>
          <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)' }}>Virtual Thread</div>
        </div>
      </div>

      {/* Detail description */}
      <div
        style={{
          padding: '0.75rem 1rem',
          borderRadius: '0.375rem',
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          fontSize: '0.8125rem',
          lineHeight: 1.5,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.25rem' }}>
          <strong style={{ color: 'var(--accent-cyan)' }}>{current.title}</strong>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-faint)', fontFamily: 'var(--font-mono)' }}>
            [{current.component}]
          </span>
        </div>
        <p style={{ color: 'var(--text-muted)' }}>{current.detail}</p>
      </div>
    </div>
  );
};
