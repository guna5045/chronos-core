import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { archiveDlqTask, getDlqTasks, inspectDlqTask, replayDlqTask } from '../api/dlq';
import { StatusBadge } from '../components/common/StatusBadge';
import { CopyableId } from '../components/common/CopyableId';
import { ConfirmModal } from '../components/common/ConfirmModal';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { EmptyState } from '../components/common/EmptyState';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { formatDate } from '../utils/formatters';
import { useAuth } from '../auth/AuthContext';
import {
  AlertTriangle,
  Archive,
  ChevronLeft,
  ChevronRight,
  Eye,
  RefreshCw,
  X,
} from 'lucide-react';

export const DlqPage: React.FC = () => {
  const { canMutateTasks } = useAuth();
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [size] = useState(20);

  // Inspection modal state
  const [inspectTaskId, setInspectTaskId] = useState<string | null>(null);

  // Mutation confirmation states
  const [replayTargetId, setReplayTargetId] = useState<string | null>(null);
  const [archiveTargetId, setArchiveTargetId] = useState<string | null>(null);

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['dlq', { page, size }],
    queryFn: () => getDlqTasks(page, size),
    refetchInterval: 10000,
  });

  const inspectQuery = useQuery({
    queryKey: ['dlqInspect', inspectTaskId],
    queryFn: () => inspectDlqTask(inspectTaskId!),
    enabled: !!inspectTaskId,
  });

  const replayMutation = useMutation({
    mutationFn: ({ taskId, reason }: { taskId: string; reason?: string }) =>
      replayDlqTask(taskId, { reason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['dlq'] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      setReplayTargetId(null);
      setInspectTaskId(null);
    },
  });

  const archiveMutation = useMutation({
    mutationFn: ({ taskId, reason }: { taskId: string; reason?: string }) =>
      archiveDlqTask(taskId, { reason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['dlq'] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      setArchiveTargetId(null);
      setInspectTaskId(null);
    },
  });

  const tasks = data?.items || [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = data?.totalPages ?? 1;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* Top Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
        <div>
          <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
            Dead-Letter Queue (DLQ)
          </h2>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
            Quarantine and operational recovery for tasks that exhausted retry policies or failed permanently
          </p>
        </div>

        <button className="btn btn-secondary btn-sm" onClick={() => refetch()}>
          <RefreshCw size={14} />
          <span>Refresh</span>
        </button>
      </div>

      {isError && <ErrorAlert error={error} onRetry={() => refetch()} />}

      {/* Main DLQ Table */}
      {isLoading ? (
        <LoadingSkeleton rows={6} height="3rem" />
      ) : tasks.length === 0 ? (
        <EmptyState
          title="Dead-Letter Queue is empty"
          description="Zero tasks currently parked in DLQ. All tasks are executing normally or have succeeded."
        />
      ) : (
        <div className="data-table-container">
          <table className="data-table">
            <thead>
              <tr>
                <th>Task ID</th>
                <th>Type</th>
                <th>Tenant</th>
                <th>Status</th>
                <th>Attempts</th>
                <th>Version</th>
                <th>Created</th>
                <th style={{ textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {tasks.map((t) => (
                <tr key={t.taskId}>
                  <td>
                    <Link to={`/tasks/${t.taskId}`} style={{ textDecoration: 'none' }}>
                      <CopyableId id={t.taskId} />
                    </Link>
                  </td>
                  <td className="font-mono" style={{ color: 'var(--accent-cyan)' }}>{t.taskType}</td>
                  <td className="font-mono">{t.tenantId}</td>
                  <td><StatusBadge status={t.status} /></td>
                  <td className="font-mono">{t.currentAttempt} / {t.maxAttempts}</td>
                  <td className="font-mono">v{t.version}</td>
                  <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>{formatDate(t.createdAt)}</td>
                  <td style={{ textAlign: 'right' }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '0.4rem' }}>
                      <button
                        className="btn btn-secondary btn-sm"
                        onClick={() => setInspectTaskId(t.taskId)}
                      >
                        <Eye size={12} />
                        <span>Inspect</span>
                      </button>

                      {canMutateTasks && (
                        <>
                          <button
                            className="btn btn-primary btn-sm"
                            title="Replay DLQ Task"
                            onClick={() => setReplayTargetId(t.taskId)}
                          >
                            <RefreshCw size={12} />
                            <span>Replay</span>
                          </button>
                          <button
                            className="btn btn-secondary btn-sm"
                            title="Archive DLQ Task"
                            onClick={() => setArchiveTargetId(t.taskId)}
                          >
                            <Archive size={12} />
                            <span>Archive</span>
                          </button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Pagination */}
          <div
            style={{
              padding: '0.85rem 1.25rem',
              borderTop: '1px solid var(--border-subtle)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              fontSize: '0.8125rem',
              color: 'var(--text-muted)',
            }}
          >
            <div>
              Showing <span style={{ color: 'var(--text-main)', fontWeight: 600 }}>{tasks.length}</span> of{' '}
              <span style={{ color: 'var(--text-main)', fontWeight: 600 }}>{totalElements}</span> tasks (Page {page + 1} of {Math.max(1, totalPages)})
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <button
                className="btn btn-secondary btn-sm"
                disabled={page <= 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                <ChevronLeft size={14} />
                <span>Prev</span>
              </button>
              <button
                className="btn btn-secondary btn-sm"
                disabled={page >= totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
              >
                <span>Next</span>
                <ChevronRight size={14} />
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Deep Inspection Modal */}
      {inspectTaskId && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            backgroundColor: 'rgba(5, 8, 15, 0.75)',
            backdropFilter: 'blur(3px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 9999,
            padding: '1rem',
          }}
          onClick={() => setInspectTaskId(null)}
        >
          <div
            style={{
              backgroundColor: 'var(--bg-surface)',
              border: '1px solid var(--border-default)',
              borderRadius: '0.5rem',
              maxWidth: '720px',
              width: '100%',
              padding: '1.5rem',
              maxHeight: '90vh',
              overflowY: 'auto',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1.25rem' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.65rem' }}>
                <AlertTriangle size={20} color="var(--accent-amber)" />
                <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-main)' }}>
                  DLQ Task Deep Inspection
                </h3>
              </div>
              <button
                onClick={() => setInspectTaskId(null)}
                style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}
              >
                <X size={18} />
              </button>
            </div>

            {inspectQuery.isLoading ? (
              <LoadingSkeleton rows={5} height="2.5rem" />
            ) : inspectQuery.isError || !inspectQuery.data ? (
              <ErrorAlert error={inspectQuery.error || new Error('Failed to inspect DLQ task')} />
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
                {/* Task Summary */}
                <div
                  style={{
                    padding: '1rem',
                    borderRadius: '0.375rem',
                    backgroundColor: 'var(--bg-elevated)',
                    border: '1px solid var(--border-subtle)',
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                    gap: '0.65rem',
                    fontSize: '0.8125rem',
                  }}
                >
                  <div>
                    <span style={{ color: 'var(--text-muted)' }}>Task ID:</span>{' '}
                    <CopyableId id={inspectQuery.data.task.taskId} />
                  </div>
                  <div>
                    <span style={{ color: 'var(--text-muted)' }}>Type:</span>{' '}
                    <span className="font-mono" style={{ color: 'var(--accent-cyan)' }}>
                      {inspectQuery.data.task.taskType}
                    </span>
                  </div>
                  <div>
                    <span style={{ color: 'var(--text-muted)' }}>Tenant:</span>{' '}
                    <span className="font-mono">{inspectQuery.data.task.tenantId}</span>
                  </div>
                  <div>
                    <span style={{ color: 'var(--text-muted)' }}>Attempts:</span>{' '}
                    <span className="font-mono">
                      {inspectQuery.data.task.currentAttempt} / {inspectQuery.data.task.maxAttempts}
                    </span>
                  </div>
                </div>

                {/* Historical Execution Attempts */}
                <div>
                  <h4 style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.5rem' }}>
                    Historical Worker Attempts ({inspectQuery.data.attempts.length})
                  </h4>
                  {inspectQuery.data.attempts.length === 0 ? (
                    <div style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>No recorded attempts.</div>
                  ) : (
                    <div className="data-table-container">
                      <table className="data-table">
                        <thead>
                          <tr>
                            <th>#</th>
                            <th>Worker ID</th>
                            <th>Status</th>
                            <th>Error Code</th>
                            <th>Error Message</th>
                            <th>Started</th>
                          </tr>
                        </thead>
                        <tbody>
                          {inspectQuery.data.attempts.map((att) => (
                            <tr key={att.attemptId}>
                              <td className="font-mono">{att.attemptNumber}</td>
                              <td className="font-mono">{att.workerId}</td>
                              <td><StatusBadge status={att.status} /></td>
                              <td className="font-mono" style={{ color: 'var(--accent-rose)', fontSize: '0.75rem' }}>
                                {att.errorCode || '—'}
                              </td>
                              <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem', maxWidth: '200px' }}>
                                {att.errorMessage || '—'}
                              </td>
                              <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>
                                {formatDate(att.startedAt)}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>

                {/* Audit Trail */}
                <div>
                  <h4 style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.5rem' }}>
                    State Transition Audit Trail ({inspectQuery.data.auditRecords.length})
                  </h4>
                  {inspectQuery.data.auditRecords.length === 0 ? (
                    <div style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>No audit trail recorded.</div>
                  ) : (
                    <div className="data-table-container">
                      <table className="data-table">
                        <thead>
                          <tr>
                            <th>From</th>
                            <th>To</th>
                            <th>Reason</th>
                            <th>Recorded At</th>
                          </tr>
                        </thead>
                        <tbody>
                          {inspectQuery.data.auditRecords.map((aud) => (
                            <tr key={aud.auditId}>
                              <td><StatusBadge status={aud.fromStatus} /></td>
                              <td><StatusBadge status={aud.toStatus} /></td>
                              <td style={{ fontSize: '0.75rem' }}>{aud.transitionReason}</td>
                              <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>
                                {formatDate(aud.recordedAt)}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>

                {/* Action buttons */}
                {canMutateTasks && (
                  <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem', marginTop: '0.5rem' }}>
                    <button
                      className="btn btn-secondary"
                      onClick={() => setArchiveTargetId(inspectQuery.data.task.taskId)}
                    >
                      <Archive size={14} />
                      <span>Archive Task</span>
                    </button>
                    <button
                      className="btn btn-primary"
                      onClick={() => setReplayTargetId(inspectQuery.data.task.taskId)}
                    >
                      <RefreshCw size={14} />
                      <span>Replay Task</span>
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Confirmation Modals */}
      {replayTargetId && (
        <ConfirmModal
          isOpen={!!replayTargetId}
          title="Replay Dead-Letter Task"
          description={`Replay task ${replayTargetId}? The task will transition from DLQ back to QUEUED for scheduling, and any blocked downstream workflow nodes will be eligible for activation upon completion.`}
          confirmLabel="Confirm Replay"
          requireReason
          isLoading={replayMutation.isPending}
          onConfirm={(reason) => replayMutation.mutate({ taskId: replayTargetId, reason })}
          onClose={() => setReplayTargetId(null)}
        />
      )}

      {archiveTargetId && (
        <ConfirmModal
          isOpen={!!archiveTargetId}
          title="Archive Dead-Letter Task"
          description={`Archive task ${archiveTargetId}? The task will remain preserved in the audit log for compliance, but will no longer be eligible for replay or automated scheduling.`}
          confirmLabel="Archive Task"
          variant="danger"
          requireReason
          isLoading={archiveMutation.isPending}
          onConfirm={(reason) => archiveMutation.mutate({ taskId: archiveTargetId, reason })}
          onClose={() => setArchiveTargetId(null)}
        />
      )}
    </div>
  );
};
