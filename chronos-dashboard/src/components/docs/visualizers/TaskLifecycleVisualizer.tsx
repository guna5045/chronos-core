import React, { useState, useEffect, useRef } from 'react';
import { Play, Pause, RotateCcw, ChevronRight, ChevronLeft } from 'lucide-react';

type Scenario = 'success' | 'retry' | 'dlq' | 'cancel';

interface Step {
  state: string;
  label: string;
  explanation: string;
  actor: 'Client' | 'Coordinator' | 'Scheduler' | 'Worker' | 'PostgreSQL';
}

const SCENARIOS: Record<Scenario, { name: string; steps: Step[] }> = {
  success: {
    name: 'Happy Path Success',
    steps: [
      {
        state: 'CREATED',
        label: 'Task Ingestion',
        explanation: 'Client sends POST /api/v1/tasks. Coordinator validates payload, tenant quotas, and idempotency key.',
        actor: 'Coordinator',
      },
      {
        state: 'QUEUED',
        label: 'Durable Queue',
        explanation: 'Task is committed to PostgreSQL chronos_tasks with status QUEUED and version 0. Awaits scheduler polling.',
        actor: 'PostgreSQL',
      },
      {
        state: 'DISPATCHED',
        label: 'Scheduler Dispatch',
        explanation: 'Leader coordinator locks row via FOR UPDATE SKIP LOCKED, selects target worker on hash ring, and sends gRPC assignment.',
        actor: 'Scheduler',
      },
      {
        state: 'RUNNING',
        label: 'Worker Execution',
        explanation: 'Worker accepts assignment, allocates virtual thread slot, marks task RUNNING, and begins compute execution.',
        actor: 'Worker',
      },
      {
        state: 'SUCCEEDED',
        label: 'Execution Success',
        explanation: 'Worker reports completion over gRPC. Coordinator commits SUCCEEDED state and execution attempt record to PostgreSQL.',
        actor: 'PostgreSQL',
      },
    ],
  },
  retry: {
    name: 'Transient Failure & Auto-Retry',
    steps: [
      {
        state: 'CREATED',
        label: 'Task Ingestion',
        explanation: 'Task is created and validated by the coordinator.',
        actor: 'Coordinator',
      },
      {
        state: 'QUEUED',
        label: 'Durable Queue',
        explanation: 'Task enters queue with maxAttempts=3, currentAttempt=0.',
        actor: 'PostgreSQL',
      },
      {
        state: 'DISPATCHED',
        label: 'First Dispatch',
        explanation: 'Dispatched to worker-1 with attempt #1.',
        actor: 'Scheduler',
      },
      {
        state: 'RUNNING',
        label: 'Execution Attempt #1',
        explanation: 'Worker executes task; transient downstream network timeout occurs.',
        actor: 'Worker',
      },
      {
        state: 'FAILED',
        label: 'Attempt Failure',
        explanation: 'Worker reports FAILED. TaskRecoveryService evaluates DefaultRetryPolicy (attempt 1 < 3).',
        actor: 'Coordinator',
      },
      {
        state: 'RETRY',
        label: 'Exponential Backoff',
        explanation: 'Task scheduledAt set to future time with exponential backoff (baseDelay * 2^1 + jitter).',
        actor: 'Scheduler',
      },
      {
        state: 'QUEUED',
        label: 'Re-queued',
        explanation: 'Backoff duration elapses. Task becomes eligible for scheduler polling again.',
        actor: 'PostgreSQL',
      },
      {
        state: 'RUNNING',
        label: 'Execution Attempt #2',
        explanation: 'Dispatched to worker-2 (attempt #2). Downstream service has recovered.',
        actor: 'Worker',
      },
      {
        state: 'SUCCEEDED',
        label: 'Final Success',
        explanation: 'Attempt #2 succeeds. Coordinator writes completion and outbox event.',
        actor: 'PostgreSQL',
      },
    ],
  },
  dlq: {
    name: 'Max Retries Exhausted to DLQ',
    steps: [
      {
        state: 'CREATED',
        label: 'Task Created',
        explanation: 'Task created with maxAttempts=2.',
        actor: 'Coordinator',
      },
      {
        state: 'QUEUED',
        label: 'Queued',
        explanation: 'Initial attempt queued.',
        actor: 'PostgreSQL',
      },
      {
        state: 'RUNNING',
        label: 'Attempt #1 Fails',
        explanation: 'Task fails on worker due to payload schema error.',
        actor: 'Worker',
      },
      {
        state: 'RETRY',
        label: 'Backoff Delay',
        explanation: 'Attempt 1 failed; backing off for attempt 2.',
        actor: 'Coordinator',
      },
      {
        state: 'RUNNING',
        label: 'Attempt #2 Fails',
        explanation: 'Attempt #2 fails again on worker with same error.',
        actor: 'Worker',
      },
      {
        state: 'DLQ',
        label: 'Dead-Letter Quarantine',
        explanation: 'Max attempts exhausted (2/2). Task permanently transitioned to DLQ status for operator inspection.',
        actor: 'Coordinator',
      },
    ],
  },
  cancel: {
    name: 'In-Flight Cancellation',
    steps: [
      {
        state: 'CREATED',
        label: 'Task Created',
        explanation: 'Long-running task submitted (sleep: 30000).',
        actor: 'Coordinator',
      },
      {
        state: 'QUEUED',
        label: 'Queued',
        explanation: 'Queued in database.',
        actor: 'PostgreSQL',
      },
      {
        state: 'RUNNING',
        label: 'Running on Worker',
        explanation: 'Worker virtual thread begins execution.',
        actor: 'Worker',
      },
      {
        state: 'CANCELLED',
        label: 'Operator Abort',
        explanation: 'Operator issues POST /tasks/{id}/cancel. Coordinator sends gRPC abort signal; worker virtual thread is interrupted.',
        actor: 'Coordinator',
      },
    ],
  },
};

export const TaskLifecycleVisualizer: React.FC = () => {
  const [scenario, setScenario] = useState<Scenario>('success');
  const [stepIndex, setStepIndex] = useState(0);
  const [isPlaying, setIsPlaying] = useState(false);
  const timerRef = useRef<number | null>(null);

  const currentScenario = SCENARIOS[scenario];
  const currentStep = currentScenario.steps[stepIndex];

  useEffect(() => {
    setStepIndex(0);
    setIsPlaying(false);
  }, [scenario]);

  useEffect(() => {
    if (isPlaying) {
      timerRef.current = window.setInterval(() => {
        setStepIndex((prev) => {
          if (prev >= currentScenario.steps.length - 1) {
            setIsPlaying(false);
            return prev;
          }
          return prev + 1;
        });
      }, 1800);
    } else if (timerRef.current) {
      clearInterval(timerRef.current);
    }
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [isPlaying, currentScenario.steps.length]);

  const handleNext = () => {
    if (stepIndex < currentScenario.steps.length - 1) setStepIndex(stepIndex + 1);
  };

  const handlePrev = () => {
    if (stepIndex > 0) setStepIndex(stepIndex - 1);
  };

  const handleReset = () => {
    setIsPlaying(false);
    setStepIndex(0);
  };

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
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <span style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase' }}>
            Interactive Scenario:
          </span>
          <select
            value={scenario}
            onChange={(e) => setScenario(e.target.value as Scenario)}
            style={{
              backgroundColor: 'var(--bg-surface)',
              color: 'var(--text-main)',
              border: '1px solid var(--border-default)',
              borderRadius: '0.375rem',
              padding: '0.25rem 0.6rem',
              fontSize: '0.8125rem',
            }}
          >
            {Object.entries(SCENARIOS).map(([key, val]) => (
              <option key={key} value={key}>
                {val.name}
              </option>
            ))}
          </select>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
          <button
            onClick={() => setIsPlaying(!isPlaying)}
            className="btn btn-secondary btn-sm"
            title={isPlaying ? 'Pause' : 'Play'}
          >
            {isPlaying ? <Pause size={13} /> : <Play size={13} />}
            <span>{isPlaying ? 'Pause' : 'Play'}</span>
          </button>
          <button onClick={handlePrev} disabled={stepIndex === 0} className="btn btn-secondary btn-sm" title="Step Back">
            <ChevronLeft size={13} />
          </button>
          <button
            onClick={handleNext}
            disabled={stepIndex === currentScenario.steps.length - 1}
            className="btn btn-secondary btn-sm"
            title="Step Forward"
          >
            <ChevronRight size={13} />
          </button>
          <button onClick={handleReset} className="btn btn-secondary btn-sm" title="Reset">
            <RotateCcw size={13} />
          </button>
        </div>
      </div>

      {/* State Machine Flow Nodes */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          overflowX: 'auto',
          padding: '1rem 0.5rem',
          gap: '0.5rem',
        }}
      >
        {currentScenario.steps.map((step, idx) => {
          const isActive = idx === stepIndex;
          const isPassed = idx < stepIndex;

          let badgeColor = 'var(--text-muted)';
          let borderColor = 'var(--border-default)';
          let bgColor = 'var(--bg-surface)';

          if (isActive) {
            borderColor = 'var(--accent-cyan)';
            bgColor = 'rgba(56, 189, 248, 0.15)';
            badgeColor = 'var(--accent-cyan)';
          } else if (isPassed) {
            borderColor = 'var(--accent-emerald)';
            badgeColor = 'var(--accent-emerald)';
          }

          if (step.state === 'FAILED' || step.state === 'DLQ') {
            if (isActive) {
              borderColor = 'var(--accent-rose)';
              bgColor = 'rgba(244, 63, 94, 0.15)';
              badgeColor = 'var(--accent-rose)';
            }
          }

          return (
            <React.Fragment key={idx}>
              <div
                onClick={() => setStepIndex(idx)}
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  padding: '0.5rem 0.75rem',
                  borderRadius: '0.375rem',
                  border: `1px solid ${borderColor}`,
                  backgroundColor: bgColor,
                  cursor: 'pointer',
                  minWidth: '95px',
                  textAlign: 'center',
                  transition: 'all 0.2s ease',
                  boxShadow: isActive ? '0 0 10px rgba(56, 189, 248, 0.3)' : 'none',
                }}
              >
                <span className="font-mono" style={{ fontSize: '0.7rem', fontWeight: 700, color: badgeColor }}>
                  {step.state}
                </span>
                <span style={{ fontSize: '0.65rem', color: 'var(--text-faint)', marginTop: '0.15rem' }}>
                  Step {idx + 1}
                </span>
              </div>
              {idx < currentScenario.steps.length - 1 && (
                <div
                  style={{
                    height: '2px',
                    flex: 1,
                    minWidth: '15px',
                    backgroundColor: isPassed ? 'var(--accent-emerald)' : 'var(--border-default)',
                    transition: 'background-color 0.2s ease',
                  }}
                />
              )}
            </React.Fragment>
          );
        })}
      </div>

      {/* Active Step Details Panel */}
      <div
        style={{
          padding: '0.85rem 1rem',
          borderRadius: '0.375rem',
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          display: 'flex',
          flexDirection: 'column',
          gap: '0.4rem',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <span
              className="font-mono"
              style={{
                fontSize: '0.75rem',
                padding: '0.15rem 0.45rem',
                borderRadius: '3px',
                backgroundColor: 'rgba(56, 189, 248, 0.15)',
                color: 'var(--accent-cyan)',
                fontWeight: 600,
              }}
            >
              {currentStep.state}
            </span>
            <span style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--text-main)' }}>
              {currentStep.label}
            </span>
          </div>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-faint)', fontFamily: 'var(--font-mono)' }}>
            Actor: {currentStep.actor}
          </span>
        </div>
        <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', lineHeight: 1.5 }}>
          {currentStep.explanation}
        </p>
      </div>
    </div>
  );
};
