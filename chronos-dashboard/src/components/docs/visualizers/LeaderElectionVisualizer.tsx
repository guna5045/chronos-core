import React, { useState, useEffect } from 'react';
import { RotateCcw, AlertTriangle, ShieldCheck, Play, Pause } from 'lucide-react';

export const LeaderElectionVisualizer: React.FC = () => {
  const [stage, setStage] = useState<'steady' | 'partition' | 'expired' | 'elected' | 'stale_rejected'>('steady');
  const [epoch, setEpoch] = useState(8);
  const [ttl, setTtl] = useState(4.2);
  const [isPlaying, setIsPlaying] = useState(false);

  useEffect(() => {
    let interval: number | null = null;
    if (stage === 'steady') {
      interval = window.setInterval(() => {
        setTtl((prev) => (prev <= 1.5 ? 5.0 : +(prev - 0.5).toFixed(1)));
      }, 500);
    }
    return () => {
      if (interval) clearInterval(interval);
    };
  }, [stage]);

  useEffect(() => {
    let timeout: number | null = null;
    if (isPlaying) {
      if (stage === 'steady') {
        timeout = window.setTimeout(() => setStage('partition'), 2000);
      } else if (stage === 'partition') {
        timeout = window.setTimeout(() => setStage('expired'), 2000);
      } else if (stage === 'expired') {
        timeout = window.setTimeout(() => {
          setEpoch(9);
          setStage('elected');
        }, 2000);
      } else if (stage === 'elected') {
        timeout = window.setTimeout(() => setStage('stale_rejected'), 2500);
      } else if (stage === 'stale_rejected') {
        timeout = window.setTimeout(() => {
          setIsPlaying(false);
        }, 3000);
      }
    }
    return () => {
      if (timeout) clearTimeout(timeout);
    };
  }, [isPlaying, stage]);

  const handleReset = () => {
    setIsPlaying(false);
    setStage('steady');
    setEpoch(8);
    setTtl(4.5);
  };

  const handleSimulateFailure = () => {
    setIsPlaying(false);
    setStage('partition');
    setTtl(0);
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
        <div>
          <span style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase' }}>
            Redis Lease &amp; Fencing Token Mechanics
          </span>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
            Lease duration: 5s • Renewal interval: 1500ms • Monotonic epoch increment on failover
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
          <button
            onClick={() => setIsPlaying(!isPlaying)}
            className="btn btn-secondary btn-sm"
          >
            {isPlaying ? <Pause size={13} /> : <Play size={13} />}
            <span>{isPlaying ? 'Pause' : 'Auto Play Simulation'}</span>
          </button>
          <button
            onClick={handleSimulateFailure}
            disabled={stage !== 'steady'}
            className="btn btn-secondary btn-sm"
            style={{ color: 'var(--accent-rose)' }}
          >
            <AlertTriangle size={13} />
            <span>Simulate Leader Crash</span>
          </button>
          <button onClick={handleReset} className="btn btn-secondary btn-sm">
            <RotateCcw size={13} />
            <span>Reset</span>
          </button>
        </div>
      </div>

      {/* Redis Lease State Bar */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0.65rem 1rem',
          borderRadius: '0.375rem',
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          fontFamily: 'var(--font-mono)',
          fontSize: '0.75rem',
        }}
      >
        <div>
          <span style={{ color: 'var(--text-muted)' }}>Redis Key: </span>
          <span style={{ color: 'var(--accent-cyan)', fontWeight: 600 }}>chronos:leader:lock</span>
          <span style={{ color: 'var(--text-faint)', marginLeft: '0.5rem' }}>
            = {stage === 'steady' || stage === 'partition' ? 'coordinator-node-1' : stage === 'elected' || stage === 'stale_rejected' ? 'coordinator-node-2' : '<EXPIRED>'}
          </span>
        </div>
        <div>
          <span style={{ color: 'var(--text-muted)' }}>TTL: </span>
          <span style={{ color: 'var(--accent-amber)', fontWeight: 600 }}>
            {stage === 'steady' ? `${ttl}s` : stage === 'partition' ? '0.0s (EXPIRED)' : stage === 'expired' ? 'NONE' : '4.8s'}
          </span>
        </div>
        <div>
          <span style={{ color: 'var(--text-muted)' }}>Epoch (Fencing): </span>
          <span style={{ color: 'var(--accent-emerald)', fontWeight: 700 }}>{epoch}</span>
        </div>
      </div>

      {/* Cluster Nodes Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '0.75rem' }}>
        {/* Node 1 */}
        <div
          style={{
            padding: '1rem',
            borderRadius: '0.375rem',
            border: `1px solid ${
              stage === 'steady'
                ? 'var(--accent-emerald)'
                : stage === 'partition'
                ? 'var(--accent-rose)'
                : 'var(--border-default)'
            }`,
            backgroundColor: stage === 'steady' ? 'rgba(16, 185, 129, 0.08)' : 'var(--bg-surface)',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.35rem',
            opacity: stage === 'partition' || stage === 'expired' ? 0.6 : 1,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span style={{ fontWeight: 600, fontSize: '0.8125rem' }}>Coordinator 1</span>
            <span
              className="badge"
              style={{
                fontSize: '0.65rem',
                backgroundColor:
                  stage === 'steady'
                    ? 'rgba(16, 185, 129, 0.2)'
                    : stage === 'partition'
                    ? 'rgba(244, 63, 94, 0.2)'
                    : 'rgba(100, 116, 139, 0.2)',
                color:
                  stage === 'steady'
                    ? 'var(--accent-emerald)'
                    : stage === 'partition'
                    ? 'var(--accent-rose)'
                    : 'var(--text-muted)',
              }}
            >
              {stage === 'steady'
                ? 'LEADER (ACTIVE)'
                : stage === 'partition'
                ? 'CRASHED / PAUSED'
                : stage === 'stale_rejected'
                ? 'STALE SPLIT-BRAIN'
                : 'STANDBY'}
            </span>
          </div>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>
            Observed Epoch: <code className="font-mono">{stage === 'stale_rejected' ? 8 : epoch}</code>
          </span>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-faint)' }}>
            {stage === 'steady'
              ? 'Scheduling loop active (polling PostgreSQL)'
              : stage === 'partition'
              ? 'JVM frozen; lease renewal ceased'
              : stage === 'stale_rejected'
              ? 'Attempts write with old Epoch 8 (Blocked!)'
              : 'Scheduling loop suspended'}
          </span>
        </div>

        {/* Node 2 */}
        <div
          style={{
            padding: '1rem',
            borderRadius: '0.375rem',
            border: `1px solid ${
              stage === 'elected' || stage === 'stale_rejected'
                ? 'var(--accent-emerald)'
                : 'var(--border-default)'
            }`,
            backgroundColor:
              stage === 'elected' || stage === 'stale_rejected'
                ? 'rgba(16, 185, 129, 0.08)'
                : 'var(--bg-surface)',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.35rem',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span style={{ fontWeight: 600, fontSize: '0.8125rem' }}>Coordinator 2</span>
            <span
              className="badge"
              style={{
                fontSize: '0.65rem',
                backgroundColor:
                  stage === 'elected' || stage === 'stale_rejected'
                    ? 'rgba(16, 185, 129, 0.2)'
                    : 'rgba(100, 116, 139, 0.2)',
                color:
                  stage === 'elected' || stage === 'stale_rejected'
                    ? 'var(--accent-emerald)'
                    : 'var(--text-muted)',
              }}
            >
              {stage === 'elected' || stage === 'stale_rejected' ? 'NEW LEADER' : 'STANDBY (FOLLOWER)'}
            </span>
          </div>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>
            Observed Epoch: <code className="font-mono">{epoch}</code>
          </span>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-faint)' }}>
            {stage === 'elected' || stage === 'stale_rejected'
              ? 'Acquired lease; resumed task scheduler loop'
              : 'Monitoring lease expiration in Redis'}
          </span>
        </div>

        {/* Node 3 */}
        <div
          style={{
            padding: '1rem',
            borderRadius: '0.375rem',
            border: '1px solid var(--border-default)',
            backgroundColor: 'var(--bg-surface)',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.35rem',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span style={{ fontWeight: 600, fontSize: '0.8125rem' }}>Coordinator 3</span>
            <span
              className="badge"
              style={{
                fontSize: '0.65rem',
                backgroundColor: 'rgba(100, 116, 139, 0.2)',
                color: 'var(--text-muted)',
              }}
            >
              STANDBY
            </span>
          </div>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>
            Observed Epoch: <code className="font-mono">{epoch}</code>
          </span>
          <span style={{ fontSize: '0.7rem', color: 'var(--text-faint)' }}>
            Scheduler loop suspended pending leadership
          </span>
        </div>
      </div>

      {/* Explanatory Callout */}
      <div
        style={{
          padding: '0.75rem 1rem',
          borderRadius: '0.375rem',
          backgroundColor:
            stage === 'stale_rejected'
              ? 'rgba(244, 63, 94, 0.1)'
              : 'var(--bg-surface)',
          border: `1px solid ${
            stage === 'stale_rejected' ? 'rgba(244, 63, 94, 0.3)' : 'var(--border-subtle)'
          }`,
          fontSize: '0.8125rem',
          lineHeight: 1.5,
          color: 'var(--text-main)',
        }}
      >
        {stage === 'steady' && (
          <div>
            <strong>Steady State:</strong> Coordinator 1 holds the lease key in Redis. It renews every 1500ms using an atomic Lua script. Standby coordinators monitor the key and keep their scheduler loops suspended.
          </div>
        )}
        {stage === 'partition' && (
          <div style={{ color: 'var(--accent-amber)' }}>
            <strong>Failure Detected:</strong> Coordinator 1 experienced a network partition or JVM freeze. The 5-second TTL expired without renewal.
          </div>
        )}
        {stage === 'expired' && (
          <div style={{ color: 'var(--accent-cyan)' }}>
            <strong>Lease Expired:</strong> Key chronos:leader:lock is deleted by Redis TTL. Standby nodes detect the vacancy and attempt atomic SET NX PX.
          </div>
        )}
        {stage === 'elected' && (
          <div style={{ color: 'var(--accent-emerald)' }}>
            <strong>New Leader Elected:</strong> Coordinator 2 won the race in Redis, incremented Epoch to 9, and resumed the scheduling and recovery loops.
          </div>
        )}
        {stage === 'stale_rejected' && (
          <div style={{ color: 'var(--accent-rose)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', fontWeight: 600, marginBottom: '0.2rem' }}>
              <ShieldCheck size={16} />
              <span>Fencing Token Invariant Enforced (STALE_LEADER_EPOCH)</span>
            </div>
            Coordinator 1 wakes up and attempts to dispatch a task using old Epoch 8. Workers check the token against the active Epoch 9 and REJECT the command. Split-brain write averted!
          </div>
        )}
      </div>
    </div>
  );
};
