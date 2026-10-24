import React, { useState } from 'react';
import { Play, RotateCcw, Bot, ShieldCheck, CheckCircle2, UserCheck } from 'lucide-react';

export const AiWorkflowVisualizer: React.FC = () => {
  const [step, setStep] = useState(0);

  const steps = [
    {
      title: '1. Natural Language Prompt',
      actor: 'Human Engineer',
      description: 'Engineer inputs prompt: "Daily billing pipeline with extract, transform, and load steps".',
      badge: 'PROMPT INPUT',
      color: 'var(--text-main)',
    },
    {
      title: '2. Injection Defense & Bounding',
      actor: 'Prompt Sanitizer',
      description: 'System checks character limit (max 4000) and wraps requirement inside strict XML boundary delimiters.',
      badge: 'SANITIZED',
      color: 'var(--accent-amber)',
    },
    {
      title: '3. AI Provider Generation',
      actor: 'AiWorkflowService',
      description: 'Generates structured JSON with nodes (extract, transform, load) and edges (extract->transform, transform->load).',
      badge: 'AI GENERATION',
      color: 'var(--accent-blue)',
    },
    {
      title: '4. Deterministic Cycle Validation',
      actor: 'Graph Validator (Kahn\'s Algorithm)',
      description: 'Chronos Core mathematically verifies graph has root nodes, zero cycles, and valid priorities. Marked valid=true.',
      badge: 'VALIDATED DAG',
      color: 'var(--accent-cyan)',
    },
    {
      title: '5. Proposal in PENDING_REVIEW',
      actor: 'Control Plane Storage',
      description: 'Proposal saved with 30-minute expiration. Notice: Workflow is NOT deployed or active yet!',
      badge: 'PENDING REVIEW',
      color: 'var(--accent-amber)',
    },
    {
      title: '6. Mandatory Human Approval Gate',
      actor: 'Platform Operator',
      description: 'Operator inspects visual graph preview on AI Workflows page and explicitly clicks Approve.',
      badge: 'HUMAN APPROVAL',
      color: 'var(--accent-purple)',
    },
    {
      title: '7. Workflow Materialization',
      actor: 'WorkflowService',
      description: 'Validated definition and Draft Version v1 are created in PostgreSQL. Human approval requirement satisfied.',
      badge: 'MATERIALIZED',
      color: 'var(--accent-emerald)',
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
            AI Workflow Agent: Deterministic Proposal Pipeline
          </span>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
            Prompt Bounding • Cycle Validation • Strict Schema • Mandatory Human Approval Gate
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
          <button
            onClick={() => setStep(Math.min(steps.length - 1, step + 1))}
            disabled={step === steps.length - 1}
            className="btn btn-secondary btn-sm"
          >
            <Play size={13} />
            <span>Next Stage</span>
          </button>
          <button onClick={() => setStep(0)} className="btn btn-secondary btn-sm">
            <RotateCcw size={13} />
            <span>Reset</span>
          </button>
        </div>
      </div>

      {/* Pipeline Stages Grid */}
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

      {/* Description Box */}
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
        {step < 3 ? (
          <Bot size={20} color="var(--accent-blue)" />
        ) : step < 5 ? (
          <ShieldCheck size={20} color="var(--accent-cyan)" />
        ) : step === 5 ? (
          <UserCheck size={20} color="var(--accent-purple)" />
        ) : (
          <CheckCircle2 size={20} color="var(--accent-emerald)" />
        )}
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.15rem' }}>
            <strong style={{ color: current.color }}>{current.title}</strong>
            <span style={{ fontSize: '0.7rem', color: 'var(--text-faint)', fontFamily: 'var(--font-mono)' }}>
              [{current.actor}]
            </span>
          </div>
          <p style={{ color: 'var(--text-muted)' }}>{current.description}</p>
        </div>
      </div>
    </div>
  );
};
