import React, { useState } from 'react';
import { Play, RotateCcw, ShieldAlert, CheckCircle2, UserCheck, ShieldCheck } from 'lucide-react';

export const AiOperationsVisualizer: React.FC = () => {
  const [step, setStep] = useState(0);

  const steps = [
    {
      title: '1. Incident Analysis Request',
      actor: 'SRE / Operator',
      desc: 'Operator initiates investigation on task in DLQ via POST /api/v1/ai/incidents/analyze.',
      badge: 'INVESTIGATION',
    },
    {
      title: '2. Live Telemetry Extraction',
      actor: 'AiOperationsPort',
      desc: 'System gathers ground-truth evidence: 3 execution attempt logs, error code TASK_INTENTIONAL_FAILURE, worker health: ACTIVE.',
      badge: 'TELEMETRY',
    },
    {
      title: '3. Evidence-Based Root Cause',
      actor: 'AiOperationsService',
      desc: 'Identifies probable causes with HIGH severity rating: transient connection timeout vs deterministic failure.',
      badge: 'DIAGNOSIS',
    },
    {
      title: '4. Bounded Action Generation',
      actor: 'Policy Engine',
      desc: 'Generates bounded recommendations (e.g. REPLAY_DLQ) with LOW/MEDIUM risk rating and 30-minute expiration window.',
      badge: 'PENDING APPROVAL',
    },
    {
      title: '5. Human Operator Review',
      actor: 'On-Call SRE',
      desc: 'Operator inspects findings and explicit action consequences on the AI Operations page. Clicks Approve.',
      badge: 'HUMAN GATE',
    },
    {
      title: '6. Stale State Verification',
      actor: 'AiActionApprovalService',
      desc: 'Pre-flight check in PostgreSQL: Verifies target task has not been concurrently replayed or cancelled before proceeding.',
      badge: 'SAFETY CHECK',
    },
    {
      title: '7. Core Platform Remediation',
      actor: 'Coordinator Engine',
      desc: 'Replay operation executes via DLQ service. Attempt number monotonically incremented. Audit log recorded.',
      badge: 'EXECUTED',
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
            AI Operations Agent: Incident Triage &amp; Guardrails
          </span>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
            Live Telemetry • Bounded Actions • Stale State Protection • Operator Approval
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

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))', gap: '0.5rem' }}>
        {steps.map((s, idx) => {
          const isActive = idx === step;
          const isPassed = idx < step;
          return (
            <div
              key={idx}
              onClick={() => setStep(idx)}
              style={{
                padding: '0.5rem 0.65rem',
                borderRadius: '0.375rem',
                border: `1px solid ${isActive ? 'var(--accent-cyan)' : isPassed ? 'var(--accent-emerald)' : 'var(--border-default)'}`,
                backgroundColor: isActive ? 'rgba(56, 189, 248, 0.12)' : 'var(--bg-surface)',
                cursor: 'pointer',
                textAlign: 'center',
                transition: 'all 0.15s ease',
              }}
            >
              <div style={{ fontSize: '0.7rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.15rem' }}>
                {s.title.split('.')[1]}
              </div>
              <span
                className="font-mono"
                style={{
                  fontSize: '0.6rem',
                  fontWeight: 700,
                  color: isActive ? 'var(--accent-cyan)' : isPassed ? 'var(--accent-emerald)' : 'var(--text-muted)',
                }}
              >
                {s.badge}
              </span>
            </div>
          );
        })}
      </div>

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
          gap: '0.75rem',
        }}
      >
        {step === 4 ? (
          <UserCheck size={20} color="var(--accent-purple)" />
        ) : step === 5 ? (
          <ShieldCheck size={20} color="var(--accent-cyan)" />
        ) : step === 6 ? (
          <CheckCircle2 size={20} color="var(--accent-emerald)" />
        ) : (
          <ShieldAlert size={20} color="var(--accent-amber)" />
        )}
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.15rem' }}>
            <strong style={{ color: 'var(--text-main)' }}>{current.title}</strong>
            <span style={{ fontSize: '0.7rem', color: 'var(--text-faint)', fontFamily: 'var(--font-mono)' }}>
              [{current.actor}]
            </span>
          </div>
          <p style={{ color: 'var(--text-muted)' }}>{current.desc}</p>
        </div>
      </div>
    </div>
  );
};
