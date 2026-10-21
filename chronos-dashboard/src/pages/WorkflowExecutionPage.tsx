import React, { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { cancelWorkflowExecution, getWorkflowExecution, getWorkflowVersions } from '../api/workflows';
import { DagVisualizer } from '../components/workflow/DagVisualizer';
import { StatusBadge } from '../components/common/StatusBadge';
import { CopyableId } from '../components/common/CopyableId';
import { ConfirmModal } from '../components/common/ConfirmModal';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { formatDate } from '../utils/formatters';
import { useAuth } from '../auth/AuthContext';
import {
  AlertCircle,
  ArrowLeft,
  RefreshCw,
  Slash,
} from 'lucide-react';

export const WorkflowExecutionPage: React.FC = () => {
  const { executionId } = useParams<{ executionId: string }>();
  const { canMutateTasks } = useAuth();
  const queryClient = useQueryClient();
  const [isCancelOpen, setIsCancelOpen] = useState(false);

  // Execution query
  const executionQuery = useQuery({
    queryKey: ['workflowExecution', executionId],
    queryFn: () => getWorkflowExecution(executionId!),
    enabled: !!executionId,
    refetchInterval: 4000,
  });

  const execDetails = executionQuery.data;
  const execution = execDetails?.execution;

  // Workflow version query for DAG structure
  const versionsQuery = useQuery({
    queryKey: ['workflowVersions', execution?.workflowId],
    queryFn: () => getWorkflowVersions(execution!.workflowId),
    enabled: !!execution?.workflowId,
  });

  const cancelMutation = useMutation({
    mutationFn: (reason?: string) =>
      cancelWorkflowExecution(executionId!, reason),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflowExecution', executionId] });
      setIsCancelOpen(false);
    },
  });

  if (executionQuery.isLoading) {
    return <LoadingSkeleton rows={6} height="3rem" />;
  }

  if (executionQuery.isError || !execution) {
    return <ErrorAlert error={executionQuery.error || new Error('Workflow execution not found')} />;
  }

  const versions = versionsQuery.data || [];
  const version = versions.find((v) => v.versionId === execution.versionId);
  const canCancel =
    canMutateTasks &&
    (execution.status === 'PENDING' || execution.status === 'RUNNING');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* Top Header */}
      <div>
        <Link
          to={`/workflows/${execution.workflowId}`}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '0.35rem',
            color: 'var(--accent-cyan)',
            fontSize: '0.8125rem',
            textDecoration: 'none',
            marginBottom: '0.5rem',
          }}
        >
          <ArrowLeft size={14} />
          <span>Workflow Definition</span>
        </Link>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
            <h2 style={{ fontSize: '1.25rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
              Execution
            </h2>
            <CopyableId id={execution.executionId} truncate={false} />
            <StatusBadge status={execution.status} type="workflow" />
            <span
              className="font-mono"
              style={{
                fontSize: '0.75rem',
                color: 'var(--accent-cyan)',
                padding: '0.15rem 0.45rem',
                backgroundColor: 'rgba(56, 189, 248, 0.1)',
                borderRadius: '3px',
              }}
            >
              v{execution.versionNumber}
            </span>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            {canCancel && (
              <button className="btn btn-danger" onClick={() => setIsCancelOpen(true)}>
                <Slash size={14} />
                <span>Cancel Execution</span>
              </button>
            )}
            <button className="btn btn-secondary btn-sm" onClick={() => executionQuery.refetch()}>
              <RefreshCw size={14} />
            </button>
          </div>
        </div>
      </div>

      {execution.failureReason && (
        <div
          style={{
            backgroundColor: 'rgba(244, 63, 94, 0.1)',
            border: '1px solid rgba(244, 63, 94, 0.3)',
            borderRadius: '0.5rem',
            padding: '1rem',
            display: 'flex',
            alignItems: 'center',
            gap: '0.75rem',
            color: '#fda4af',
            fontSize: '0.8125rem',
          }}
        >
          <AlertCircle size={18} />
          <div>
            <strong style={{ fontWeight: 600 }}>Workflow Execution Failure:</strong>{' '}
            {execution.failureReason}
          </div>
        </div>
      )}

      {/* Execution Telemetry Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: '1.25rem' }}>
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-subtle)',
            borderRadius: '0.5rem',
            padding: '1.25rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.65rem',
          }}
        >
          <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase' }}>
            Binding &amp; Tenant
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '110px 1fr', gap: '0.4rem', fontSize: '0.8125rem' }}>
            <span style={{ color: 'var(--text-muted)' }}>Workflow ID:</span>
            <Link to={`/workflows/${execution.workflowId}`} style={{ color: 'var(--accent-cyan)', textDecoration: 'none' }}>
              <CopyableId id={execution.workflowId} />
            </Link>

            <span style={{ color: 'var(--text-muted)' }}>Tenant:</span>
            <span className="font-mono">{execution.tenantId}</span>

            <span style={{ color: 'var(--text-muted)' }}>Pinned Version:</span>
            <span className="font-mono">v{execution.versionNumber}</span>
          </div>
        </div>

        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-subtle)',
            borderRadius: '0.5rem',
            padding: '1.25rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.65rem',
          }}
        >
          <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase' }}>
            Temporal Progression
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '110px 1fr', gap: '0.4rem', fontSize: '0.8125rem' }}>
            <span style={{ color: 'var(--text-muted)' }}>Started At:</span>
            <span>{formatDate(execution.startedAt)}</span>

            <span style={{ color: 'var(--text-muted)' }}>Completed At:</span>
            <span>{formatDate(execution.completedAt)}</span>
          </div>
        </div>
      </div>

      {/* Real DAG State Visualizer */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.5rem',
          padding: '1.25rem',
          display: 'flex',
          flexDirection: 'column',
          gap: '1rem',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div>
            <h3 style={{ fontSize: '0.95rem', fontWeight: 600, color: 'var(--text-main)' }}>
              Execution DAG State Machine
            </h3>
            <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
              Live node execution progress • Colored by actual lifecycle status
            </p>
          </div>
        </div>

        {version ? (
          <DagVisualizer
            nodes={version.nodes}
            edges={version.edges}
            executionNodes={execDetails?.nodes}
          />
        ) : (
          <LoadingSkeleton rows={4} height="4rem" />
        )}
      </div>

      {/* Node Executions Table */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.5rem',
          padding: '1.25rem',
        }}
      >
        <h3 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '1rem' }}>
          Node Execution Records ({execDetails?.nodes?.length ?? 0})
        </h3>

        <div className="data-table-container">
          <table className="data-table">
            <thead>
              <tr>
                <th>Node ID</th>
                <th>Status</th>
                <th>Chronos Task ID</th>
                <th>Started</th>
                <th>Completed</th>
                <th>Diagnostics</th>
              </tr>
            </thead>
            <tbody>
              {execDetails?.nodes?.map((n) => (
                <tr key={n.nodeId}>
                  <td className="font-mono" style={{ fontWeight: 600, color: 'var(--text-main)' }}>
                    {n.nodeId}
                  </td>
                  <td><StatusBadge status={n.status} type="workflow" /></td>
                  <td>
                    {n.taskId ? (
                      <Link to={`/tasks/${n.taskId}`} style={{ textDecoration: 'none' }}>
                        <CopyableId id={n.taskId} />
                      </Link>
                    ) : (
                      <span style={{ color: 'var(--text-faint)', fontSize: '0.75rem' }}>Not created</span>
                    )}
                  </td>
                  <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>{formatDate(n.startedAt)}</td>
                  <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>{formatDate(n.completedAt)}</td>
                  <td style={{ color: n.failureReason ? 'var(--accent-rose)' : 'var(--text-muted)', fontSize: '0.75rem' }}>
                    {n.failureReason || '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Cancel Execution Modal */}
      {isCancelOpen && (
        <ConfirmModal
          isOpen={isCancelOpen}
          title="Cancel Workflow Execution"
          description={`Are you sure you want to cancel execution ${execution.executionId}? Pending nodes will be cancelled and active workers will receive cooperative cancellation.`}
          confirmLabel="Cancel Workflow"
          variant="danger"
          requireReason
          isLoading={cancelMutation.isPending}
          onConfirm={(reason) => cancelMutation.mutate(reason)}
          onClose={() => setIsCancelOpen(false)}
        />
      )}
    </div>
  );
};
