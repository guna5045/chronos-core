import React, { useState } from 'react';
import { RotateCcw, Shuffle, Server } from 'lucide-react';

export const ConsistentHashVisualizer: React.FC = () => {
  const [worker2Active, setWorker2Active] = useState(true);
  const [taskKey, setTaskKey] = useState('task-billing-01');
  const [activeTaskAngle, setActiveTaskAngle] = useState(45);

  const workers = [
    { id: 'worker-1', label: 'Worker 1', color: 'var(--accent-cyan)', angles: [0, 90, 180, 270] },
    { id: 'worker-2', label: 'Worker 2', color: 'var(--accent-emerald)', angles: [30, 120, 210, 300], active: worker2Active },
    { id: 'worker-3', label: 'Worker 3', color: 'var(--accent-amber)', angles: [60, 150, 240, 330] },
  ];

  // Calculate assigned worker clockwise
  const getAssignedWorker = (angle: number): { id: string; label: string; color: string } => {
    const activeNodes: { angle: number; workerId: string }[] = [];
    workers.forEach((w) => {
      if (w.active !== false) {
        w.angles.forEach((a) => activeNodes.push({ angle: a, workerId: w.id }));
      }
    });
    activeNodes.sort((a, b) => a.angle - b.angle);

    const match = activeNodes.find((n) => n.angle >= angle) || activeNodes[0];
    const worker = workers.find((w) => w.id === match.workerId)!;
    return { id: worker.id, label: worker.label, color: worker.color };
  };

  const assigned = getAssignedWorker(activeTaskAngle);

  const handleRandomizeTask = () => {
    const randomAngle = Math.floor(Math.random() * 360);
    const randomSuffix = Math.floor(1000 + Math.random() * 9000);
    setTaskKey(`task-order-${randomSuffix}`);
    setActiveTaskAngle(randomAngle);
  };

  const ringRadius = 90;
  const center = 110;

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
            Consistent Hashing Ring &amp; Virtual Nodes
          </span>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
            128 virtual nodes/worker • Clockwise point resolution • Minimal remapping on topology change
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
          <button onClick={handleRandomizeTask} className="btn btn-secondary btn-sm">
            <Shuffle size={13} />
            <span>Randomize Task Hash</span>
          </button>
          <button
            onClick={() => setWorker2Active(!worker2Active)}
            className="btn btn-secondary btn-sm"
            style={{ color: worker2Active ? 'var(--accent-rose)' : 'var(--accent-emerald)' }}
          >
            <Server size={13} />
            <span>{worker2Active ? 'Kill Worker 2' : 'Restore Worker 2'}</span>
          </button>
          <button
            onClick={() => {
              setWorker2Active(true);
              setActiveTaskAngle(45);
              setTaskKey('task-billing-01');
            }}
            className="btn btn-secondary btn-sm"
          >
            <RotateCcw size={13} />
          </button>
        </div>
      </div>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '240px 1fr',
          gap: '1.5rem',
          alignItems: 'center',
          backgroundColor: 'var(--bg-surface)',
          padding: '1rem',
          borderRadius: '0.375rem',
          border: '1px solid var(--border-subtle)',
        }}
      >
        {/* SVG Ring Visualization */}
        <div style={{ display: 'flex', justifyContent: 'center' }}>
          <svg width="220" height="220" viewBox="0 0 220 220">
            {/* Base Ring */}
            <circle
              cx={center}
              cy={center}
              r={ringRadius}
              fill="none"
              stroke="var(--border-default)"
              strokeWidth="4"
              strokeDasharray="4 4"
            />

            {/* Virtual Node Points */}
            {workers.map((worker) => {
              if (worker.active === false) return null;
              return worker.angles.map((deg) => {
                const rad = (deg - 90) * (Math.PI / 180);
                const x = center + ringRadius * Math.cos(rad);
                const y = center + ringRadius * Math.sin(rad);
                return (
                  <circle
                    key={`${worker.id}-${deg}`}
                    cx={x}
                    cy={y}
                    r="5"
                    fill={worker.color}
                    stroke="var(--bg-canvas)"
                    strokeWidth="1.5"
                  />
                );
              });
            })}

            {/* Active Task Point */}
            {(() => {
              const rad = (activeTaskAngle - 90) * (Math.PI / 180);
              const tx = center + ringRadius * Math.cos(rad);
              const ty = center + ringRadius * Math.sin(rad);
              return (
                <g>
                  <circle cx={tx} cy={ty} r="8" fill="var(--accent-rose)" opacity="0.4" />
                  <circle cx={tx} cy={ty} r="4.5" fill="var(--accent-rose)" stroke="#fff" strokeWidth="1.5" />
                  <line
                    x1={center}
                    y1={center}
                    x2={tx}
                    y2={ty}
                    stroke="var(--accent-rose)"
                    strokeWidth="1"
                    strokeDasharray="2 2"
                    opacity="0.6"
                  />
                </g>
              );
            })()}

            <text
              x={center}
              y={center - 5}
              textAnchor="middle"
              fill="var(--text-main)"
              fontSize="10"
              fontWeight="bold"
            >
              HASH RING
            </text>
            <text
              x={center}
              y={center + 12}
              textAnchor="middle"
              fill="var(--text-faint)"
              fontSize="8"
              fontFamily="var(--font-mono)"
            >
              0° - 360°
            </text>
          </svg>
        </div>

        {/* Informational Panel */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.65rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
            <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Task ID:</span>
            <code className="font-mono" style={{ fontSize: '0.8rem', color: 'var(--accent-cyan)', fontWeight: 600 }}>
              {taskKey}
            </code>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
              (Hash Point: {activeTaskAngle}°)
            </span>
          </div>

          <div
            style={{
              padding: '0.6rem 0.85rem',
              borderRadius: '0.375rem',
              backgroundColor: 'var(--bg-elevated)',
              border: `1px solid ${assigned.color}`,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
            }}
          >
            <div>
              <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>Clockwise Destination: </span>
              <strong style={{ fontSize: '0.85rem', color: assigned.color }}>{assigned.label}</strong>
            </div>
            <span
              className="badge"
              style={{
                fontSize: '0.65rem',
                backgroundColor: 'rgba(56, 189, 248, 0.1)',
                color: assigned.color,
              }}
            >
              Virtual Node Assigned
            </span>
          </div>

          <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', lineHeight: 1.5 }}>
            {!worker2Active ? (
              <span style={{ color: 'var(--accent-amber)' }}>
                <strong>Worker 2 Evicted:</strong> Notice that Worker 2\'s virtual nodes disappeared. Tasks that formerly mapped to Worker 2 immediately route to the next clockwise neighbor without reshuffling tasks on Worker 1!
              </span>
            ) : (
              <span>
                <strong>Even Distribution:</strong> With 128 virtual nodes per worker, the hash ring evenly distributes keys across all active physical workers, preventing single-worker overload.
              </span>
            )}
          </p>
        </div>
      </div>
    </div>
  );
};
