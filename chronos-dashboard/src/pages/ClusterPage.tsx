import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { getClusterOverview } from '../api/cluster';
import { getWorkers } from '../api/workers';
import { MetricCard } from '../components/common/MetricCard';
import { StatusBadge } from '../components/common/StatusBadge';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { Link } from 'react-router-dom';
import { Cpu, RefreshCw, Server, Shield, Zap } from 'lucide-react';
import { LearnAboutPageButton } from '../components/docs/LearnAboutPageButton';

export const ClusterPage: React.FC = () => {
  const clusterQuery = useQuery({
    queryKey: ['clusterOverview'],
    queryFn: getClusterOverview,
    refetchInterval: 5000,
  });

  const workersQuery = useQuery({
    queryKey: ['workers'],
    queryFn: getWorkers,
    refetchInterval: 5000,
  });

  const isLoading = clusterQuery.isLoading || workersQuery.isLoading;

  if (isLoading) {
    return <LoadingSkeleton rows={6} height="3rem" />;
  }

  if (clusterQuery.isError) {
    return <ErrorAlert error={clusterQuery.error} onRetry={() => clusterQuery.refetch()} />;
  }

  const cluster = clusterQuery.data;
  const workers = workersQuery.data || [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* Top Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
        <div>
          <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
            Cluster Coordination &amp; Topology
          </h2>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            Redis lease-based leader election, generational fencing tokens, and worker dispatch topology
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
          <LearnAboutPageButton pageId="cluster" />
          <button className="btn btn-secondary btn-sm" onClick={() => { clusterQuery.refetch(); workersQuery.refetch(); }}>
            <RefreshCw size={14} />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {/* Cluster Overview Cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '1rem' }}>
        <MetricCard
          title="Coordinator Role"
          value={cluster?.isLeader ? 'ACTIVE LEADER' : 'FOLLOWER / STANDBY'}
          subtitle={`Node: ${cluster?.coordinatorId ?? 'unknown'}`}
          icon={<Server size={20} />}
          accentColor={cluster?.isLeader ? 'var(--accent-emerald)' : 'var(--accent-amber)'}
        />
        <MetricCard
          title="Fencing Epoch"
          value={`#${cluster?.leaderEpoch ?? 0}`}
          subtitle="Generational lease increment"
          icon={<Shield size={20} />}
          accentColor="var(--accent-cyan)"
        />
        <MetricCard
          title="Fleet Size"
          value={`${cluster?.activeWorkers ?? 0} active`}
          subtitle={`${cluster?.totalWorkers ?? 0} registered workers`}
          icon={<Cpu size={20} />}
          accentColor="var(--accent-blue)"
        />
        <MetricCard
          title="Cluster Concurrency"
          value={`${cluster?.availableClusterCapacity ?? 0} / ${cluster?.totalClusterCapacity ?? 0}`}
          subtitle={`${cluster?.activeClusterTasks ?? 0} active task executions`}
          icon={<Zap size={20} />}
          accentColor="var(--accent-emerald)"
        />
      </div>

      {/* Cluster Topology Visualizer */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.5rem',
          padding: '1.5rem',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: '1.5rem',
        }}
      >
        <div style={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div>
            <h3 style={{ fontSize: '0.95rem', fontWeight: 600, color: 'var(--text-main)' }}>
              Coordinator &mdash; Worker Dispatch Topology
            </h3>
            <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
              Active leader dispatches tasks to registered workers over gRPC with streaming heartbeats
            </p>
          </div>
          <span
            className="font-mono"
            style={{
              fontSize: '0.7rem',
              color: 'var(--accent-emerald)',
              padding: '0.15rem 0.5rem',
              borderRadius: '4px',
              backgroundColor: 'rgba(16, 185, 129, 0.1)',
            }}
          >
            LEASE HEALTHY
          </span>
        </div>

        {/* Coordinator Node Box */}
        <div
          style={{
            padding: '1.25rem 2rem',
            borderRadius: '0.5rem',
            backgroundColor: 'var(--bg-elevated)',
            border: '2px solid var(--accent-cyan)',
            boxShadow: '0 0 15px rgba(56, 189, 248, 0.15)',
            textAlign: 'center',
            maxWidth: '380px',
            width: '100%',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '0.5rem', marginBottom: '0.4rem' }}>
            <Server size={18} color="var(--accent-cyan)" />
            <span style={{ fontSize: '0.95rem', fontWeight: 700, color: 'var(--text-main)' }}>
              Active Coordinator Leader
            </span>
          </div>
          <div className="font-mono" style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            {cluster?.coordinatorId}
          </div>
          <div
            style={{
              fontSize: '0.7rem',
              color: 'var(--accent-cyan)',
              fontFamily: 'var(--font-mono)',
              marginTop: '0.35rem',
            }}
          >
            Epoch #{cluster?.leaderEpoch} • Lease TTL: 5s
          </div>
        </div>

        {/* Connecting Line */}
        <div
          style={{
            width: '2px',
            height: '28px',
            backgroundColor: 'var(--border-default)',
          }}
        />

        {/* Worker Fleet Nodes */}
        {workers.length === 0 ? (
          <div
            style={{
              padding: '1.5rem',
              borderRadius: '0.375rem',
              backgroundColor: 'var(--bg-canvas)',
              border: '1px dashed var(--border-default)',
              color: 'var(--text-muted)',
              fontSize: '0.8125rem',
              textAlign: 'center',
              width: '100%',
            }}
          >
            Zero workers connected. Start a <code className="font-mono">chronos-worker</code> instance to establish gRPC communication.
          </div>
        ) : (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
              gap: '1rem',
              width: '100%',
            }}
          >
            {workers.map((w) => (
              <div
                key={w.workerId}
                style={{
                  padding: '1rem',
                  borderRadius: '0.375rem',
                  backgroundColor: 'var(--bg-canvas)',
                  border: '1px solid var(--border-default)',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '0.4rem',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <Link
                    to={`/workers/${w.workerId}`}
                    style={{ textDecoration: 'none', color: 'var(--text-main)' }}
                  >
                    <span className="font-mono" style={{ fontSize: '0.8125rem', fontWeight: 600 }}>
                      {w.workerId}
                    </span>
                  </Link>
                  <StatusBadge status={w.state} type="worker" />
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                  {w.hostname}:{w.port}
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--accent-cyan)', fontFamily: 'var(--font-mono)' }}>
                  Capacity: {w.activeTasks} / {w.maxConcurrency}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
