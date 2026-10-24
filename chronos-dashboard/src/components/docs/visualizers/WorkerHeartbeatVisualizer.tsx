import React, { useState, useEffect } from 'react';
import { Play, Pause, RotateCcw, AlertOctagon, Heart, Radio } from 'lucide-react';

export const WorkerHeartbeatVisualizer: React.FC = () => {
  const [elapsed, setElapsed] = useState(0);
  const [isDead, setIsDead] = useState(false);
  const [recovering, setRecovering] = useState(false);
  const [reassigned, setReassigned] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);

  useEffect(() => {
    let timer: number | null = null;
    if (isPlaying) {
      timer = window.setInterval(() => {
        setElapsed((prev) => +(prev + 0.5).toFixed(1));
      }, 500);
    }
    return () => {
      if (timer) clearInterval(timer);
    };
  }, [isPlaying]);

  useEffect(() => {
    if (isDead) {
      if (elapsed >= 3.0 && elapsed < 6.0 && !reassigned) {
        // UNHEALTHY
      } else if (elapsed >= 6.0 && !reassigned) {
        setReassigned(true);
      } else if (elapsed >= 8.5 && !recovering) {
        setRecovering(true);
      } else if (elapsed >= 10.0) {
        setIsDead(false);
        setRecovering(false);
        setElapsed(0.5);
      }
    }
  }, [elapsed, isDead, reassigned, recovering]);

  const handleSimulateLoss = () => {
    setIsDead(true);
    setElapsed(0);
    setReassigned(false);
    setRecovering(false);
    setIsPlaying(true);
  };

  const handleReset = () => {
    setIsPlaying(false);
    setIsDead(false);
    setRecovering(false);
    setReassigned(false);
    setElapsed(0.5);
  };

  const worker1Status = !isDead
    ? 'ACTIVE'
    : elapsed < 3.0
    ? 'ACTIVE'
    : elapsed < 6.0
    ? 'UNHEALTHY'
    : recovering
    ? 'RECOVERING'
    : 'REMOVED';

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
            Worker Heartbeat &amp; Health Monitoring
          </span>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
            Interval: 1s • Heartbeat timeout: 3s (UNHEALTHY) • Eviction timeout: 6s (REMOVED)
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
          <button
            onClick={() => setIsPlaying(!isPlaying)}
            className="btn btn-secondary btn-sm"
          >
            {isPlaying ? <Pause size={13} /> : <Play size={13} />}
            <span>{isPlaying ? 'Pause' : 'Auto Pulse'}</span>
          </button>
          <button
            onClick={handleSimulateLoss}
            disabled={isDead}
            className="btn btn-secondary btn-sm"
            style={{ color: 'var(--accent-rose)' }}
          >
            <AlertOctagon size={13} />
            <span>Simulate Worker Death</span>
          </button>
          <button onClick={handleReset} className="btn btn-secondary btn-sm">
            <RotateCcw size={13} />
            <span>Reset</span>
          </button>
        </div>
      </div>

      {/* Visual Pipeline Container */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '1.2fr 1.6fr 1.2fr',
          gap: '1rem',
          alignItems: 'center',
          padding: '1rem',
          backgroundColor: 'var(--bg-surface)',
          borderRadius: '0.375rem',
          border: '1px solid var(--border-subtle)',
        }}
      >
        {/* Worker Node 1 */}
        <div
          style={{
            padding: '1rem',
            borderRadius: '0.375rem',
            border: `1px solid ${
              worker1Status === 'ACTIVE'
                ? 'var(--accent-emerald)'
                : worker1Status === 'UNHEALTHY'
                ? 'var(--accent-amber)'
                : 'var(--accent-rose)'
            }`,
            backgroundColor: 'var(--bg-elevated)',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.4rem',
            textAlign: 'center',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '0.4rem' }}>
            <Radio size={14} color={worker1Status === 'ACTIVE' ? 'var(--accent-emerald)' : 'var(--text-muted)'} />
            <span style={{ fontWeight: 600, fontSize: '0.85rem' }}>Worker 1</span>
          </div>
          <span
            className="badge"
            style={{
              fontSize: '0.65rem',
              alignSelf: 'center',
              backgroundColor:
                worker1Status === 'ACTIVE'
                  ? 'rgba(16, 185, 129, 0.2)'
                  : worker1Status === 'UNHEALTHY'
                  ? 'rgba(245, 158, 11, 0.2)'
                  : 'rgba(244, 63, 94, 0.2)',
              color:
                worker1Status === 'ACTIVE'
                  ? 'var(--accent-emerald)'
                  : worker1Status === 'UNHEALTHY'
                  ? 'var(--accent-amber)'
                  : 'var(--accent-rose)',
            }}
          >
            {worker1Status}
          </span>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-faint)', fontFamily: 'var(--font-mono)' }}>
            {!isDead ? 'Pinging: 1.0s interval' : `Last ping: ${elapsed}s ago`}
          </span>
        </div>

        {/* Heartbeat Transit Stream */}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '0.4rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', color: isDead ? 'var(--text-faint)' : 'var(--accent-emerald)' }}>
            <Heart size={16} style={{ animation: isDead ? 'none' : 'spin 2s linear infinite' }} />
            <span style={{ fontSize: '0.75rem', fontWeight: 600 }}>
              {!isDead ? 'Heartbeat Stream (gRPC)' : 'Stream Interrupted!'}
            </span>
          </div>

          <div
            style={{
              width: '100%',
              height: '4px',
              backgroundColor: isDead ? 'var(--border-default)' : 'rgba(16, 185, 129, 0.3)',
              borderRadius: '2px',
              position: 'relative',
              overflow: 'hidden',
            }}
          >
            {!isDead && (
              <div
                style={{
                  width: '30%',
                  height: '100%',
                  backgroundColor: 'var(--accent-emerald)',
                  borderRadius: '2px',
                  animation: 'spin 1.5s linear infinite',
                }}
              />
            )}
          </div>

          <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>
            Missed Heartbeats: <code className="font-mono">{isDead ? Math.floor(elapsed) : 0}</code>
          </span>
        </div>

        {/* Coordinator Node */}
        <div
          style={{
            padding: '1rem',
            borderRadius: '0.375rem',
            border: '1px solid var(--border-default)',
            backgroundColor: 'var(--bg-elevated)',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.4rem',
            textAlign: 'center',
          }}
        >
          <span style={{ fontWeight: 600, fontSize: '0.85rem' }}>Coordinator Leader</span>
          <span style={{ fontSize: '0.7rem', color: 'var(--accent-cyan)' }}>WorkerHealthMonitor</span>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-faint)' }}>
            {reassigned ? 'Task reassigned to Worker 2' : 'Tracking liveness'}
          </span>
        </div>
      </div>

      {/* Explanatory Timeline */}
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
        {!isDead && (
          <div>
            <strong>Steady State (Healthy):</strong> Worker streams gRPC heartbeat pings every 1000ms. Coordinator updates in-memory registry. Worker is eligible for task dispatch on hash ring.
          </div>
        )}
        {isDead && elapsed < 3.0 && (
          <div style={{ color: 'var(--text-muted)' }}>
            <strong>Missed Heartbeat ({elapsed}s):</strong> Pings stopped arriving. Coordinator observes silence but allows up to 3s grace before changing state.
          </div>
        )}
        {isDead && elapsed >= 3.0 && elapsed < 6.0 && (
          <div style={{ color: 'var(--accent-amber)' }}>
            <strong>Threshold 1 Breached (3s - UNHEALTHY):</strong> Worker marked UNHEALTHY. New task dispatches are suspended for this node.
          </div>
        )}
        {isDead && elapsed >= 6.0 && !recovering && (
          <div style={{ color: 'var(--accent-rose)' }}>
            <strong>Threshold 2 Breached (6s - EVICTION):</strong> Worker marked REMOVED. Virtual nodes removed from hash ring. RecoveryScheduler reclaims in-flight task leases and reassigns them to healthy surviving workers.
          </div>
        )}
        {recovering && (
          <div style={{ color: 'var(--accent-emerald)' }}>
            <strong>Auto-Rejoin Handshake:</strong> Worker restarts, calls RegisterWorker gRPC, receives new session UUID, and returns to ACTIVE.
          </div>
        )}
      </div>
    </div>
  );
};
