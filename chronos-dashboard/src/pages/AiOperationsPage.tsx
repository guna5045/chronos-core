import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { aiApi } from '../api/ai';
import {
  AiActionRisk,
  AiIncidentSeverity,
  AiOperationalAction,
  IncidentAnalysisRequest,
  IncidentAnalysisResponse,
  IncidentScope,
} from '../api/types';
import { CopyableId } from '../components/common/CopyableId';
import { StatusBadge } from '../components/common/StatusBadge';
import { ConfirmModal } from '../components/common/ConfirmModal';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { formatDate } from '../utils/formatters';
import { useAuth } from '../auth/AuthContext';
import {
  Activity,
  AlertOctagon,
  AlertTriangle,
  CheckCircle2,
  Clock,
  Cpu,
  RefreshCw,
  Search,
  ShieldAlert,
  Terminal,
  ThumbsDown,
  ThumbsUp,
} from 'lucide-react';

const SEVERITY_COLORS: Record<AiIncidentSeverity, { bg: string; text: string; border: string }> = {
  INFO: { bg: 'rgba(56, 189, 248, 0.1)', text: 'var(--accent-cyan)', border: 'rgba(56, 189, 248, 0.3)' },
  LOW: { bg: 'rgba(16, 185, 129, 0.1)', text: 'var(--accent-emerald)', border: 'rgba(16, 185, 129, 0.3)' },
  MEDIUM: { bg: 'rgba(245, 158, 11, 0.1)', text: 'var(--accent-amber)', border: 'rgba(245, 158, 11, 0.3)' },
  HIGH: { bg: 'rgba(249, 115, 22, 0.1)', text: '#fb923c', border: 'rgba(249, 115, 22, 0.3)' },
  CRITICAL: { bg: 'rgba(244, 63, 94, 0.15)', text: 'var(--accent-rose)', border: 'rgba(244, 63, 94, 0.4)' },
};

const RISK_COLORS: Record<AiActionRisk, { bg: string; text: string }> = {
  LOW: { bg: 'rgba(16, 185, 129, 0.15)', text: 'var(--accent-emerald)' },
  MEDIUM: { bg: 'rgba(245, 158, 11, 0.15)', text: 'var(--accent-amber)' },
  HIGH: { bg: 'rgba(244, 63, 94, 0.15)', text: 'var(--accent-rose)' },
};

export const AiOperationsPage: React.FC = () => {
  const { user, canMutateTasks } = useAuth();
  const queryClient = useQueryClient();

  // Investigation form state
  const [scope, setScope] = useState<IncidentScope>('CLUSTER');
  const [targetId, setTargetId] = useState('');

  // Results & Interaction state
  const [latestAnalysis, setLatestAnalysis] = useState<IncidentAnalysisResponse | null>(null);
  const [actionToApprove, setActionToApprove] = useState<AiOperationalAction | null>(null);
  const [actionToReject, setActionToReject] = useState<AiOperationalAction | null>(null);
  const [executionResultMsg, setExecutionResultMsg] = useState<{ type: 'success' | 'warning' | 'error'; text: string } | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // AI Status query
  const { data: aiStatus } = useQuery({
    queryKey: ['ai-status'],
    queryFn: aiApi.getStatus,
    refetchInterval: 60000,
  });

  // Action registry query
  const {
    data: actions,
    isLoading: isLoadingActions,
    refetch: refetchActions,
  } = useQuery<AiOperationalAction[]>({
    queryKey: ['ai-actions'],
    queryFn: () => aiApi.listActions(user?.tenantId),
    refetchInterval: 8000,
  });

  // Analyze incident mutation
  const analyzeMutation = useMutation({
    mutationFn: (req: IncidentAnalysisRequest) => aiApi.analyzeIncident(req),
    onSuccess: (data: IncidentAnalysisResponse) => {
      setLatestAnalysis(data);
      setErrorMsg(null);
      setExecutionResultMsg(null);
      queryClient.invalidateQueries({ queryKey: ['ai-actions'] });
    },
    onError: (err: Error) => {
      setErrorMsg(err.message);
    },
  });

  // Approve action mutation
  const approveMutation = useMutation({
    mutationFn: ({ actionId, reason }: { actionId: string; reason?: string }) =>
      aiApi.approveAction(actionId, reason),
    onSuccess: (data: AiOperationalAction) => {
      setActionToApprove(null);
      setExecutionResultMsg({
        type: 'success',
        text: `Action executed successfully: ${data.executionResult || 'Target state updated'}`,
      });
      queryClient.invalidateQueries({ queryKey: ['ai-actions'] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      queryClient.invalidateQueries({ queryKey: ['workers'] });
      queryClient.invalidateQueries({ queryKey: ['dlq'] });
    },
    onError: (err: Error) => {
      setActionToApprove(null);
      setExecutionResultMsg({
        type: 'error',
        text: `Execution failed: ${err.message}`,
      });
    },
  });

  // Reject action mutation
  const rejectMutation = useMutation({
    mutationFn: ({ actionId, reason }: { actionId: string; reason?: string }) =>
      aiApi.rejectAction(actionId, reason),
    onSuccess: () => {
      setActionToReject(null);
      setExecutionResultMsg({
        type: 'warning',
        text: 'Operational recommendation rejected and marked as discarded.',
      });
      queryClient.invalidateQueries({ queryKey: ['ai-actions'] });
    },
    onError: (err: Error) => {
      setActionToReject(null);
      setErrorMsg(err.message);
    },
  });

  const handleAnalyze = (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);
    setExecutionResultMsg(null);
    analyzeMutation.mutate({
      scope,
      targetId: targetId.trim() || undefined,
      tenantId: user?.tenantId,
    });
  };

  const getTargetLink = (source: string, id: string) => {
    const src = source.toLowerCase();
    if (src.includes('worker')) return `/workers/${id}`;
    if (src.includes('task')) return `/tasks/${id}`;
    if (src.includes('dlq')) return `/dlq`;
    if (src.includes('workflow')) return `/workflows/${id}`;
    return null;
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.75rem' }}>
      {/* Page Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.65rem' }}>
            <div
              style={{
                width: '32px',
                height: '32px',
                borderRadius: '6px',
                backgroundColor: 'rgba(56, 189, 248, 0.15)',
                border: '1px solid rgba(56, 189, 248, 0.4)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: 'var(--accent-cyan)',
              }}
            >
              <ShieldAlert size={18} />
            </div>
            <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
              AI Operations Agent
            </h2>
          </div>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginTop: '0.25rem' }}>
            Evidence-based cluster triage, failure correlation, and human-in-the-loop operational actions.
          </p>
        </div>

        {/* AI System Badge */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '0.5rem',
              padding: '0.35rem 0.75rem',
              borderRadius: '0.375rem',
              backgroundColor: 'var(--bg-elevated)',
              border: '1px solid var(--border-default)',
              fontSize: '0.75rem',
            }}
          >
            <Cpu size={14} color={aiStatus?.enabled ? 'var(--accent-emerald)' : 'var(--text-muted)'} />
            <span style={{ color: 'var(--text-muted)' }}>AI Engine:</span>
            <span className="font-mono" style={{ fontWeight: 600, color: aiStatus?.enabled ? 'var(--accent-emerald)' : 'var(--text-faint)' }}>
              {aiStatus?.enabled ? `${aiStatus.provider} (${aiStatus.model})` : 'DISABLED'}
            </span>
          </div>

          <button className="btn btn-secondary btn-sm" onClick={() => refetchActions()} title="Refresh Actions">
            <RefreshCw size={14} />
          </button>
        </div>
      </div>

      {errorMsg && <ErrorAlert error={new Error(errorMsg)} onRetry={() => setErrorMsg(null)} />}

      {/* Execution Feedback Banner */}
      {executionResultMsg && (
        <div
          style={{
            padding: '0.85rem 1rem',
            borderRadius: '0.375rem',
            backgroundColor:
              executionResultMsg.type === 'success'
                ? 'rgba(16, 185, 129, 0.1)'
                : executionResultMsg.type === 'warning'
                ? 'rgba(245, 158, 11, 0.1)'
                : 'rgba(244, 63, 94, 0.1)',
            border: `1px solid ${
              executionResultMsg.type === 'success'
                ? 'rgba(16, 185, 129, 0.3)'
                : executionResultMsg.type === 'warning'
                ? 'rgba(245, 158, 11, 0.3)'
                : 'rgba(244, 63, 94, 0.3)'
            }`,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            fontSize: '0.875rem',
            color:
              executionResultMsg.type === 'success'
                ? 'var(--accent-emerald)'
                : executionResultMsg.type === 'warning'
                ? 'var(--accent-amber)'
                : 'var(--accent-rose)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            {executionResultMsg.type === 'success' ? (
              <CheckCircle2 size={16} />
            ) : executionResultMsg.type === 'warning' ? (
              <AlertTriangle size={16} />
            ) : (
              <AlertOctagon size={16} />
            )}
            <span>{executionResultMsg.text}</span>
          </div>
          <button
            onClick={() => setExecutionResultMsg(null)}
            style={{ background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', fontSize: '0.8rem' }}
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Incident Investigation Input Panel */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-default)',
          borderRadius: '0.5rem',
          padding: '1.5rem',
          display: 'flex',
          flexDirection: 'column',
          gap: '1.25rem',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <Search size={18} color="var(--accent-cyan)" />
          <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-main)' }}>
            Telemetry Correlation & Incident Analysis
          </h3>
        </div>

        <form onSubmit={handleAnalyze} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '1rem' }}>
          <div>
            <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
              Investigation Scope *
            </label>
            <select
              className="select-field"
              value={scope}
              onChange={(e) => setScope(e.target.value as IncidentScope)}
              style={{ width: '100%' }}
            >
              <option value="CLUSTER">CLUSTER &mdash; Overall Health & Partition Detection</option>
              <option value="WORKER">WORKER &mdash; Node Capacity & Heartbeat Failures</option>
              <option value="TASK">TASK &mdash; Failure Root Cause & Timeout Analysis</option>
              <option value="DLQ">DLQ &mdash; Poison Pill & Exhausted Retry Analysis</option>
            </select>
          </div>

          <div>
            <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
              Target Identifier {scope === 'CLUSTER' ? '(Optional)' : '(Recommended)'}
            </label>
            <input
              type="text"
              className="input-field"
              placeholder={scope === 'WORKER' ? 'e.g. worker-01' : scope === 'TASK' ? 'e.g. task-uuid' : 'Leave empty for cluster'}
              value={targetId}
              onChange={(e) => setTargetId(e.target.value)}
            />
          </div>

          <div style={{ gridColumn: '1 / -1', display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: '0.5rem' }}>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
              Strictly reads verified live telemetry &bull; Mutations require human approval
            </span>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={analyzeMutation.isPending}
            >
              <Activity size={15} />
              <span>{analyzeMutation.isPending ? 'Collecting Telemetry & Diagnosing...' : 'Run Incident Diagnosis'}</span>
            </button>
          </div>
        </form>
      </div>

      {/* Incident Diagnosis Results Panel */}
      {latestAnalysis && (
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-default)',
            borderRadius: '0.5rem',
            padding: '1.5rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '1.5rem',
          }}
        >
          {/* Findings Header */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.65rem' }}>
                <span
                  style={{
                    fontSize: '0.75rem',
                    fontFamily: 'var(--font-mono)',
                    padding: '0.2rem 0.55rem',
                    borderRadius: '0.25rem',
                    backgroundColor: SEVERITY_COLORS[latestAnalysis.severity].bg,
                    color: SEVERITY_COLORS[latestAnalysis.severity].text,
                    border: `1px solid ${SEVERITY_COLORS[latestAnalysis.severity].border}`,
                    fontWeight: 700,
                  }}
                >
                  {latestAnalysis.severity} SEVERITY
                </span>
                <span
                  style={{
                    fontSize: '0.75rem',
                    fontFamily: 'var(--font-mono)',
                    color: 'var(--text-muted)',
                  }}
                >
                  SCOPE: {latestAnalysis.scope} {latestAnalysis.targetId ? `(${latestAnalysis.targetId})` : ''}
                </span>
              </div>
              <h3 style={{ fontSize: '1.15rem', fontWeight: 600, color: 'var(--text-main)', marginTop: '0.5rem' }}>
                {latestAnalysis.summary}
              </h3>
            </div>
            <div style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
              Analysis ID: <CopyableId id={latestAnalysis.analysisId} />
            </div>
          </div>

          {/* Likely Root Causes */}
          {latestAnalysis.likelyCauses && latestAnalysis.likelyCauses.length > 0 && (
            <div
              style={{
                padding: '1rem 1.25rem',
                borderRadius: '0.375rem',
                backgroundColor: 'var(--bg-elevated)',
                border: '1px solid var(--border-subtle)',
              }}
            >
              <div style={{ fontSize: '0.8125rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.5rem' }}>
                Likely Root Causes & Failure Patterns:
              </div>
              <ul style={{ paddingLeft: '1.25rem', margin: 0, fontSize: '0.8125rem', color: 'var(--text-muted)', lineHeight: 1.6 }}>
                {latestAnalysis.likelyCauses.map((cause, idx) => (
                  <li key={idx}>{cause}</li>
                ))}
              </ul>
            </div>
          )}

          {/* Verified Telemetry Evidence */}
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.65rem' }}>
              <Terminal size={16} color="var(--accent-cyan)" />
              <h4 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-main)' }}>
                Verified Telemetry Evidence ({latestAnalysis.evidence?.length || 0})
              </h4>
            </div>

            {!latestAnalysis.evidence || latestAnalysis.evidence.length === 0 ? (
              <div style={{ fontSize: '0.8125rem', color: 'var(--text-faint)' }}>
                No active failures or anomaly telemetry detected in scope.
              </div>
            ) : (
              <div className="data-table-container">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Telemetry Source</th>
                      <th>Target Entity</th>
                      <th>Observed Evidence & Details</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {latestAnalysis.evidence.map((ev, idx) => {
                      const link = getTargetLink(ev.source, ev.identifier);
                      return (
                        <tr key={idx}>
                          <td>
                            <span
                              className="font-mono"
                              style={{
                                fontSize: '0.7rem',
                                padding: '0.1rem 0.35rem',
                                borderRadius: '3px',
                                backgroundColor: 'var(--bg-elevated)',
                                color: 'var(--accent-cyan)',
                                border: '1px solid var(--border-subtle)',
                              }}
                            >
                              {ev.source}
                            </span>
                          </td>
                          <td>
                            {link ? (
                              <Link to={link} style={{ textDecoration: 'none' }}>
                                <CopyableId id={ev.identifier} />
                              </Link>
                            ) : (
                              <CopyableId id={ev.identifier} />
                            )}
                          </td>
                          <td style={{ fontSize: '0.8125rem' }}>
                            <div style={{ fontWeight: 500, color: 'var(--text-main)' }}>{ev.summary}</div>
                          </td>
                          <td>
                            <StatusBadge status={ev.status} />
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {/* Recommended Controlled Actions */}
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.75rem' }}>
              <ShieldAlert size={16} color="var(--accent-amber)" />
              <h4 style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-main)' }}>
                Recommended Controlled Actions ({latestAnalysis.recommendations?.length || 0})
              </h4>
            </div>

            {!latestAnalysis.recommendations || latestAnalysis.recommendations.length === 0 ? (
              <div style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
                No mutating interventions recommended at this time.
              </div>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '1rem' }}>
                {latestAnalysis.recommendations.map((action: AiOperationalAction) => (
                  <div
                    key={action.actionId}
                    style={{
                      backgroundColor: 'var(--bg-elevated)',
                      border: '1px solid var(--border-default)',
                      borderRadius: '0.5rem',
                      padding: '1.25rem',
                      display: 'flex',
                      flexDirection: 'column',
                      justifyContent: 'space-between',
                      gap: '1rem',
                    }}
                  >
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
                        <span
                          className="font-mono"
                          style={{
                            fontSize: '0.7rem',
                            fontWeight: 600,
                            padding: '0.15rem 0.45rem',
                            borderRadius: '0.25rem',
                            backgroundColor: 'rgba(2, 132, 199, 0.15)',
                            color: 'var(--accent-cyan)',
                            border: '1px solid rgba(2, 132, 199, 0.3)',
                          }}
                        >
                          {action.actionType}
                        </span>

                        <span
                          style={{
                            fontSize: '0.7rem',
                            fontFamily: 'var(--font-mono)',
                            padding: '0.15rem 0.45rem',
                            borderRadius: '0.25rem',
                            backgroundColor: RISK_COLORS[action.risk].bg,
                            color: RISK_COLORS[action.risk].text,
                            fontWeight: 600,
                          }}
                        >
                          RISK: {action.risk}
                        </span>
                      </div>

                      <h5 style={{ fontSize: '0.95rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.35rem' }}>
                        {action.title}
                      </h5>

                      <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', lineHeight: 1.5, marginBottom: '0.75rem' }}>
                        {action.reason}
                      </p>

                      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.35rem', fontSize: '0.75rem' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
                          <span style={{ color: 'var(--text-faint)' }}>Target:</span>
                          <span className="font-mono" style={{ color: 'var(--text-main)' }}>{action.targetId}</span>
                        </div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
                          <Clock size={12} color="var(--text-faint)" />
                          <span style={{ color: 'var(--text-faint)' }}>Expires:</span>
                          <span style={{ color: 'var(--text-muted)' }}>{formatDate(action.expiresAt)}</span>
                        </div>
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '0.5rem', paddingTop: '0.75rem', borderTop: '1px solid var(--border-subtle)' }}>
                      <button
                        className="btn btn-secondary btn-sm"
                        onClick={() => setActionToReject(action)}
                        disabled={!canMutateTasks || action.status !== 'PENDING_APPROVAL'}
                      >
                        <ThumbsDown size={12} />
                        <span>Discard</span>
                      </button>
                      <button
                        className="btn btn-primary btn-sm"
                        onClick={() => setActionToApprove(action)}
                        disabled={!canMutateTasks || action.status !== 'PENDING_APPROVAL'}
                        title={!canMutateTasks ? 'OPERATOR or ADMIN role required' : ''}
                      >
                        <ThumbsUp size={12} />
                        <span>Approve & Execute</span>
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Action Audit & Operational Proposals Registry Table */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <h3 style={{ fontSize: '1.05rem', fontWeight: 600, color: 'var(--text-main)' }}>
            Operational Actions Registry
          </h3>
          <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
            All actions subject to stale state verification & bounded TTL
          </span>
        </div>

        {isLoadingActions ? (
          <LoadingSkeleton rows={5} height="2.5rem" />
        ) : !actions || actions.length === 0 ? (
          <div
            style={{
              padding: '2rem',
              textAlign: 'center',
              backgroundColor: 'var(--bg-surface)',
              border: '1px dashed var(--border-default)',
              borderRadius: '0.5rem',
              color: 'var(--text-muted)',
              fontSize: '0.875rem',
            }}
          >
            No operational action recommendations recorded yet.
          </div>
        ) : (
          <div className="data-table-container">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Action ID</th>
                  <th>Type</th>
                  <th>Target</th>
                  <th>Risk</th>
                  <th>Reason</th>
                  <th>Status</th>
                  <th>Expires</th>
                  <th style={{ textAlign: 'right' }}>Gate</th>
                </tr>
              </thead>
              <tbody>
                {actions.map((act: AiOperationalAction) => (
                  <tr key={act.actionId}>
                    <td>
                      <CopyableId id={act.actionId} />
                    </td>
                    <td>
                      <span
                        className="font-mono"
                        style={{
                          fontSize: '0.7rem',
                          padding: '0.1rem 0.35rem',
                          borderRadius: '3px',
                          backgroundColor: 'var(--bg-elevated)',
                          color: 'var(--accent-cyan)',
                          border: '1px solid var(--border-subtle)',
                        }}
                      >
                        {act.actionType}
                      </span>
                    </td>
                    <td className="font-mono" style={{ fontSize: '0.75rem' }}>
                      {act.targetId}
                    </td>
                    <td>
                      <span
                        style={{
                          fontSize: '0.7rem',
                          fontFamily: 'var(--font-mono)',
                          padding: '0.1rem 0.35rem',
                          borderRadius: '3px',
                          backgroundColor: RISK_COLORS[act.risk].bg,
                          color: RISK_COLORS[act.risk].text,
                        }}
                      >
                        {act.risk}
                      </span>
                    </td>
                    <td style={{ color: 'var(--text-muted)', maxWidth: '280px', fontSize: '0.8125rem' }}>
                      {act.reason}
                    </td>
                    <td>
                      <StatusBadge status={act.status} />
                    </td>
                    <td style={{ color: 'var(--text-faint)', fontSize: '0.75rem' }}>
                      {formatDate(act.expiresAt)}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      {act.status === 'PENDING_APPROVAL' ? (
                        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.4rem' }}>
                          <button
                            className="btn btn-secondary btn-sm"
                            onClick={() => setActionToReject(act)}
                            disabled={!canMutateTasks}
                          >
                            Reject
                          </button>
                          <button
                            className="btn btn-primary btn-sm"
                            onClick={() => setActionToApprove(act)}
                            disabled={!canMutateTasks}
                          >
                            Approve
                          </button>
                        </div>
                      ) : (
                        <span style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
                          {act.status === 'EXECUTED' ? 'Executed' : act.status}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Confirmation Modals */}
      <ConfirmModal
        isOpen={!!actionToApprove}
        title={`Approve Action: ${actionToApprove?.actionType}`}
        description={`This action will be verified against current live target state (stale state check) and executed via Chronos application services. Action target: ${actionToApprove?.targetId}. Reason: ${actionToApprove?.reason}`}
        confirmLabel="Approve & Execute"
        requireReason={true}
        isLoading={approveMutation.isPending}
        onConfirm={(reason) =>
          actionToApprove &&
          approveMutation.mutate({ actionId: actionToApprove.actionId, reason })
        }
        onClose={() => setActionToApprove(null)}
      />

      <ConfirmModal
        isOpen={!!actionToReject}
        title="Discard Operational Recommendation"
        description={`Are you sure you want to discard recommendation "${actionToReject?.title}"? It will be marked as REJECTED and cannot be executed.`}
        confirmLabel="Discard Recommendation"
        variant="warning"
        requireReason={true}
        isLoading={rejectMutation.isPending}
        onConfirm={(reason) =>
          actionToReject &&
          rejectMutation.mutate({ actionId: actionToReject.actionId, reason })
        }
        onClose={() => setActionToReject(null)}
      />
    </div>
  );
};
