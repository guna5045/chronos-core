import React, { useState, useEffect, useRef } from 'react';
import { Play, Pause, RotateCcw, ChevronRight, ChevronLeft, AlertTriangle } from 'lucide-react';

type DagScenario = 'diamond' | 'sequential' | 'failure' | 'cycle';

interface DagNode {
  id: string;
  label: string;
  status: 'READY' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';
  x: number;
  y: number;
}

interface DagEdge {
  from: string;
  to: string;
}

export const DagExecutionVisualizer: React.FC = () => {
  const [scenario, setScenario] = useState<DagScenario>('diamond');
  const [step, setStep] = useState(0);
  const [isPlaying, setIsPlaying] = useState(false);
  const timerRef = useRef<number | null>(null);

  // Define steps per scenario
  const getScenarioData = (sc: DagScenario, currentStep: number) => {
    if (sc === 'diamond') {
      const nodes: DagNode[] = [
        { id: 'A', label: 'Start (A)', status: currentStep >= 1 ? (currentStep === 1 ? 'RUNNING' : 'SUCCEEDED') : 'READY', x: 50, y: 80 },
        { id: 'B', label: 'Extract (B)', status: currentStep >= 2 ? (currentStep === 2 ? 'RUNNING' : 'SUCCEEDED') : 'READY', x: 170, y: 35 },
        { id: 'C', label: 'Validate (C)', status: currentStep >= 2 ? (currentStep === 2 ? 'RUNNING' : 'SUCCEEDED') : 'READY', x: 170, y: 80 },
        { id: 'D', label: 'Transform (D)', status: currentStep >= 2 ? (currentStep === 2 ? 'RUNNING' : 'SUCCEEDED') : 'READY', x: 170, y: 125 },
        { id: 'E', label: 'Load (E)', status: currentStep >= 3 ? (currentStep === 3 ? 'RUNNING' : 'SUCCEEDED') : 'READY', x: 290, y: 80 },
      ];
      const edges: DagEdge[] = [
        { from: 'A', to: 'B' },
        { from: 'A', to: 'C' },
        { from: 'A', to: 'D' },
        { from: 'B', to: 'E' },
        { from: 'C', to: 'E' },
        { from: 'D', to: 'E' },
      ];
      const maxSteps = 4;
      const descriptions = [
        'Execution initial: Root node A is READY to be dispatched.',
        'Root execution: Node A is RUNNING on worker fleet.',
        'Fan-Out: Node A completed. Downstream nodes B, C, and D are activated and execute concurrently in parallel!',
        'Barrier Synchronization (Fan-In): B, C, and D have all completed. Node E is activated and runs.',
        'Workflow Complete: All nodes SUCCEEDED. Overall execution status marked SUCCEEDED.',
      ];
      return { nodes, edges, maxSteps, description: descriptions[currentStep] || '' };
    }

    if (sc === 'sequential') {
      const nodes: DagNode[] = [
        { id: 'A', label: 'Extract', status: currentStep >= 1 ? (currentStep === 1 ? 'RUNNING' : 'SUCCEEDED') : 'READY', x: 60, y: 80 },
        { id: 'B', label: 'Transform', status: currentStep >= 2 ? (currentStep === 2 ? 'RUNNING' : 'SUCCEEDED') : 'READY', x: 180, y: 80 },
        { id: 'C', label: 'Load', status: currentStep >= 3 ? (currentStep === 3 ? 'RUNNING' : 'SUCCEEDED') : 'READY', x: 300, y: 80 },
      ];
      const edges: DagEdge[] = [
        { from: 'A', to: 'B' },
        { from: 'B', to: 'C' },
      ];
      const maxSteps = 3;
      const descriptions = [
        'Sequential pipeline: Step A awaits execution.',
        'Node A executing. Node B waits for A to complete.',
        'Node A finished. Node B executing. Node C awaits B.',
        'Node B finished. Node C completed. Pipeline finished successfully.',
      ];
      return { nodes, edges, maxSteps, description: descriptions[currentStep] || '' };
    }

    if (sc === 'failure') {
      const nodes: DagNode[] = [
        { id: 'A', label: 'Ingest (A)', status: currentStep >= 1 ? (currentStep === 1 ? 'RUNNING' : 'SUCCEEDED') : 'READY', x: 60, y: 80 },
        { id: 'B', label: 'Compute (B)', status: currentStep >= 2 ? (currentStep === 2 ? 'RUNNING' : 'FAILED') : 'READY', x: 180, y: 40 },
        { id: 'C', label: 'Report (C)', status: currentStep >= 3 ? 'SKIPPED' : 'READY', x: 300, y: 40 },
        { id: 'D', label: 'Audit (D)', status: currentStep >= 2 ? (currentStep === 2 ? 'RUNNING' : 'SUCCEEDED') : 'READY', x: 180, y: 120 },
      ];
      const edges: DagEdge[] = [
        { from: 'A', to: 'B' },
        { from: 'B', to: 'C' },
        { from: 'A', to: 'D' },
      ];
      const maxSteps = 3;
      const descriptions = [
        'Initial state: Root node A ready.',
        'Parallel execution: Ingest A succeeded. Nodes B and D running concurrently.',
        'Node Failure: Node B failed. Node D succeeded on independent branch.',
        'Cascading Skip: Downstream node C is marked SKIPPED due to upstream failure of B. Workflow marked FAILED.',
      ];
      return { nodes, edges, maxSteps, description: descriptions[currentStep] || '' };
    }

    // Cycle scenario
    const nodes: DagNode[] = [
      { id: 'A', label: 'Node A', status: 'READY', x: 90, y: 45 },
      { id: 'B', label: 'Node B', status: 'READY', x: 260, y: 45 },
      { id: 'C', label: 'Node C', status: 'FAILED', x: 175, y: 125 },
    ];
    const edges: DagEdge[] = [
      { from: 'A', to: 'B' },
      { from: 'B', to: 'C' },
      { from: 'C', to: 'A' },
    ];
    return {
      nodes,
      edges,
      maxSteps: 1,
      description:
        'Validation Rejection (HTTP 400 INVALID_DAG): Topological cycle detected: A -> B -> C -> A. Chronos Core rejects publishing this draft to prevent infinite graph deadlocks.',
    };
  };

  const { nodes, edges, maxSteps, description } = getScenarioData(scenario, step);

  useEffect(() => {
    setStep(0);
    setIsPlaying(false);
  }, [scenario]);

  useEffect(() => {
    if (isPlaying) {
      timerRef.current = window.setInterval(() => {
        setStep((prev) => {
          if (prev >= maxSteps) {
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
  }, [isPlaying, maxSteps]);

  const getNodeColor = (status: DagNode['status']) => {
    switch (status) {
      case 'SUCCEEDED':
        return { border: 'var(--accent-emerald)', bg: 'rgba(16, 185, 129, 0.15)', text: 'var(--accent-emerald)' };
      case 'RUNNING':
        return { border: 'var(--accent-cyan)', bg: 'rgba(56, 189, 248, 0.2)', text: 'var(--accent-cyan)' };
      case 'FAILED':
        return { border: 'var(--accent-rose)', bg: 'rgba(244, 63, 94, 0.2)', text: 'var(--accent-rose)' };
      case 'SKIPPED':
        return { border: 'var(--text-faint)', bg: 'rgba(100, 116, 139, 0.15)', text: 'var(--text-faint)' };
      default:
        return { border: 'var(--border-default)', bg: 'var(--bg-elevated)', text: 'var(--text-muted)' };
    }
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
            DAG Topology Scenario:
          </span>
          <select
            value={scenario}
            onChange={(e) => setScenario(e.target.value as DagScenario)}
            style={{
              backgroundColor: 'var(--bg-surface)',
              color: 'var(--text-main)',
              border: '1px solid var(--border-default)',
              borderRadius: '0.375rem',
              padding: '0.25rem 0.6rem',
              fontSize: '0.8125rem',
            }}
          >
            <option value="diamond">Fan-Out / Fan-In Diamond</option>
            <option value="sequential">Sequential Pipeline (A -&gt; B -&gt; C)</option>
            <option value="failure">Node Failure &amp; Cascading Skip</option>
            <option value="cycle">Cyclic Graph Rejection (A -&gt; B -&gt; A)</option>
          </select>
        </div>

        {scenario !== 'cycle' && (
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
            <button
              onClick={() => setIsPlaying(!isPlaying)}
              className="btn btn-secondary btn-sm"
            >
              {isPlaying ? <Pause size={13} /> : <Play size={13} />}
              <span>{isPlaying ? 'Pause' : 'Play Workflow'}</span>
            </button>
            <button
              onClick={() => setStep(Math.max(0, step - 1))}
              disabled={step === 0}
              className="btn btn-secondary btn-sm"
            >
              <ChevronLeft size={13} />
            </button>
            <button
              onClick={() => setStep(Math.min(maxSteps, step + 1))}
              disabled={step === maxSteps}
              className="btn btn-secondary btn-sm"
            >
              <ChevronRight size={13} />
            </button>
            <button onClick={() => { setIsPlaying(false); setStep(0); }} className="btn btn-secondary btn-sm">
              <RotateCcw size={13} />
            </button>
          </div>
        )}
      </div>

      {/* SVG Canvas for DAG Graph */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.375rem',
          padding: '1rem',
          display: 'flex',
          justifyContent: 'center',
          overflowX: 'auto',
        }}
      >
        <svg width="360" height="170" viewBox="0 0 360 170">
          <defs>
            <marker
              id="arrow"
              viewBox="0 0 10 10"
              refX="16"
              refY="5"
              markerWidth="6"
              markerHeight="6"
              orient="auto-start-reverse"
            >
              <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--border-accent)" />
            </marker>
          </defs>

          {/* Edges */}
          {edges.map((e, idx) => {
            const from = nodes.find((n) => n.id === e.from)!;
            const to = nodes.find((n) => n.id === e.to)!;
            return (
              <line
                key={idx}
                x1={from.x}
                y1={from.y}
                x2={to.x}
                y2={to.y}
                stroke="var(--border-default)"
                strokeWidth="2"
                markerEnd="url(#arrow)"
              />
            );
          })}

          {/* Nodes */}
          {nodes.map((n) => {
            const colors = getNodeColor(n.status);
            return (
              <g key={n.id}>
                <rect
                  x={n.x - 42}
                  y={n.y - 18}
                  width="84"
                  height="36"
                  rx="6"
                  fill={colors.bg}
                  stroke={colors.border}
                  strokeWidth="2"
                />
                <text
                  x={n.x}
                  y={n.y - 2}
                  textAnchor="middle"
                  fill="var(--text-main)"
                  fontSize="11"
                  fontWeight="600"
                >
                  {n.label}
                </text>
                <text
                  x={n.x}
                  y={n.y + 11}
                  textAnchor="middle"
                  fill={colors.text}
                  fontSize="8"
                  fontWeight="bold"
                  fontFamily="var(--font-mono)"
                >
                  {n.status}
                </text>
              </g>
            );
          })}
        </svg>
      </div>

      {/* Description Box */}
      <div
        style={{
          padding: '0.75rem 1rem',
          borderRadius: '0.375rem',
          backgroundColor: scenario === 'cycle' ? 'rgba(244, 63, 94, 0.1)' : 'var(--bg-surface)',
          border: `1px solid ${scenario === 'cycle' ? 'rgba(244, 63, 94, 0.3)' : 'var(--border-subtle)'}`,
          fontSize: '0.8125rem',
          lineHeight: 1.5,
          color: 'var(--text-main)',
        }}
      >
        {scenario === 'cycle' && (
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', color: 'var(--accent-rose)', fontWeight: 600, marginBottom: '0.2rem' }}>
            <AlertTriangle size={15} />
            <span>Cycle Validation Engine</span>
          </div>
        )}
        {description}
      </div>
    </div>
  );
};
