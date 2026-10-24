import React, { useState } from 'react';
import { RotateCcw, Play, CheckCircle2, AlertTriangle, RefreshCw } from 'lucide-react';

export const DlqLifecycleVisualizer: React.FC = () => {
  const [stage, setStage] = useState<'initial' | 'failed_1' | 'retrying' | 'failed_2' | 'dlq' | 'replaying' | 'succeeded'>('initial');

  const steps = [
    { key: 'initial', label: '1. Ingestion', status: 'QUEUED', desc: 'Task submitted with maxAttempts=2. Initial attempt #1 queued.' },
    { key: 'failed_1', label: '2. Attempt 1 Failed', status: 'FAILED', desc: 'Worker reported failure. TaskRecoveryService evaluates retry policy (1 < 2).' },
    { key: 'retrying', label: '3. Backoff Delay', status: 'RETRY', desc: 'Exponential backoff calculated with jitter. Task rescheduled.' },
    { key: 'failed_2', label: '4. Attempt 2 Failed', status: 'FAILED', desc: 'Second attempt fails on worker. Maximum retry limit reached (2/2).' },
    { key: 'dlq', label: '5. Quarantined in DLQ', status: 'DLQ', desc: 'Task transitioned to DLQ status. Error code DLQ_MAX_ATTEMPTS saved with stack trace.' },
    { key: 'replaying', label: '6. Operator Replay', status: 'QUEUED', desc: 'Operator calls POST /dlq/{id}/replay. Monotonic attempt logic sets next attempt to #3.' },
    { key: 'succeeded', label: '7. Final Execution', status: 'SUCCEEDED', desc: 'Task dispatched as attempt #3; succeeds. Audit log records full recovery cycle.' },
  ];

  const handleNext = () => {
    const order: Array<typeof stage> = ['initial', 'failed_1', 'retrying', 'failed_2', 'dlq', 'replaying', 'succeeded'];
    const idx = order.indexOf(stage);
    if (idx < order.length - 1) setStage(order[idx + 1]);
  };

  const handleReset = () => setStage('initial');

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
            Dead-Letter Queue Quarantine &amp; Replay Lifecycle
          </span>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
            Retry exhaustion • Root cause preservation • Monotonically incremented replay attempts
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
          <button
            onClick={handleNext}
            disabled={stage === 'succeeded'}
            className="btn btn-secondary btn-sm"
          >
            <Play size={13} />
            <span>Next Step</span>
          </button>
          <button onClick={handleReset} className="btn btn-secondary btn-sm">
            <RotateCcw size={13} />
            <span>Reset</span>
          </button>
        </div>
      </div>

      {/* Progress Cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))', gap: '0.5rem' }}>
        {steps.map((s, idx) => {
          const isActive = s.key === stage;
          const isPassed = steps.findIndex((x) => x.key === stage) > idx;

          let borderColor = 'var(--border-default)';
          let badgeColor = 'var(--text-muted)';
          let bgColor = 'var(--bg-surface)';

          if (isActive) {
            borderColor = s.status === 'DLQ' ? 'var(--accent-rose)' : 'var(--accent-cyan)';
            bgColor = s.status === 'DLQ' ? 'rgba(244, 63, 94, 0.15)' : 'rgba(56, 189, 248, 0.15)';
            badgeColor = s.status === 'DLQ' ? 'var(--accent-rose)' : 'var(--accent-cyan)';
          } else if (isPassed) {
            borderColor = 'var(--accent-emerald)';
            badgeColor = 'var(--accent-emerald)';
          }

          return (
            <div
              key={s.key}
              onClick={() => setStage(s.key as typeof stage)}
              style={{
                padding: '0.65rem 0.75rem',
                borderRadius: '0.375rem',
                border: `1px solid ${borderColor}`,
                backgroundColor: bgColor,
                cursor: 'pointer',
                textAlign: 'center',
                transition: 'all 0.15s ease',
              }}
            >
              <div style={{ fontSize: '0.7rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.2rem' }}>
                {s.label}
              </div>
              <span
                className="font-mono"
                style={{
                  fontSize: '0.65rem',
                  fontWeight: 700,
                  color: badgeColor,
                }}
              >
                {s.status}
              </span>
            </div>
          );
        })}
      </div>

      {/* Detail explanation */}
      {(() => {
        const activeItem = steps.find((s) => s.key === stage)!;
        return (
          <div
            style={{
              padding: '0.75rem 1rem',
              borderRadius: '0.375rem',
              backgroundColor: 'var(--bg-surface)',
              border: '1px solid var(--border-subtle)',
              fontSize: '0.8125rem',
              lineHeight: 1.5,
              display: 'flex',
              alignItems: 'center',
              gap: '0.6rem',
            }}
          >
            {activeItem.status === 'DLQ' ? (
              <AlertTriangle size={18} color="var(--accent-rose)" />
            ) : activeItem.status === 'SUCCEEDED' ? (
              <CheckCircle2 size={18} color="var(--accent-emerald)" />
            ) : (
              <RefreshCw size={18} color="var(--accent-cyan)" />
            )}
            <div>
              <strong>{activeItem.label}:</strong> {activeItem.desc}
            </div>
          </div>
        );
      })()}
    </div>
  );
};
