import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { aiApi } from '../api/ai';
import { AiWorkflowProposal, WorkflowEdge, WorkflowNodeResponse } from '../api/types';
import { DagVisualizer } from '../components/workflow/DagVisualizer';
import { CopyableId } from '../components/common/CopyableId';
import { StatusBadge } from '../components/common/StatusBadge';
import { ConfirmModal } from '../components/common/ConfirmModal';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { formatDate } from '../utils/formatters';
import { useAuth } from '../auth/AuthContext';
import {
  Bot,
  CheckCircle2,
  Cpu,
  GitBranch,
  RefreshCw,
  Sparkles,
  ThumbsDown,
  ThumbsUp,
  XCircle,
} from 'lucide-react';
import { LearnAboutPageButton } from '../components/docs/LearnAboutPageButton';

const SAMPLE_PROMPTS = [
  {
    label: 'Image Pipeline',
    prompt: 'Download an image, resize it, then run moderation and upload the result.',
  },
  {
    label: 'ETL Pipeline',
    prompt: 'Extract raw sales data from source, transform and aggregate hourly metrics, and load into data warehouse.',
  },
  {
    label: 'ML Model Inference',
    prompt: 'Preprocess audio file, generate transcript with speech model, and run sentiment analysis on transcript.',
  },
];

export const AiWorkflowsPage: React.FC = () => {
  const { user, canMutateTasks } = useAuth();
  const queryClient = useQueryClient();

  const [prompt, setPrompt] = useState('');
  const [selectedProposal, setSelectedProposal] = useState<AiWorkflowProposal | null>(null);
  const [isApproveModalOpen, setIsApproveModalOpen] = useState(false);
  const [isRejectModalOpen, setIsRejectModalOpen] = useState(false);
  const [createdWorkflowId, setCreatedWorkflowId] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // AI Status query
  const { data: aiStatus } = useQuery({
    queryKey: ['ai-status'],
    queryFn: aiApi.getStatus,
    refetchInterval: 60000,
  });

  // Proposal list query
  const {
    data: proposals,
    isLoading: isLoadingProposals,
    refetch: refetchProposals,
  } = useQuery<AiWorkflowProposal[]>({
    queryKey: ['ai-workflow-proposals'],
    queryFn: () => aiApi.listProposals(user?.tenantId),
    refetchInterval: 10000,
  });

  // Generate proposal mutation
  const generateMutation = useMutation({
    mutationFn: (userPrompt: string) =>
      aiApi.generateWorkflowProposal({
        prompt: userPrompt,
        tenantId: user?.tenantId,
      }),
    onSuccess: (data: AiWorkflowProposal) => {
      setSelectedProposal(data);
      setErrorMsg(null);
      setCreatedWorkflowId(null);
      queryClient.invalidateQueries({ queryKey: ['ai-workflow-proposals'] });
    },
    onError: (err: Error) => {
      setErrorMsg(err.message);
    },
  });

  // Approve proposal mutation
  const approveMutation = useMutation({
    mutationFn: (proposalId: string) => aiApi.approveWorkflowProposal(proposalId),
    onSuccess: (res: { workflowId: string; name: string; version: number; status: string }) => {
      setIsApproveModalOpen(false);
      setCreatedWorkflowId(res.workflowId);
      queryClient.invalidateQueries({ queryKey: ['ai-workflow-proposals'] });
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
      if (selectedProposal) {
        setSelectedProposal({
          ...selectedProposal,
          status: 'APPROVED',
        });
      }
    },
    onError: (err: Error) => {
      setErrorMsg(err.message);
      setIsApproveModalOpen(false);
    },
  });

  // Reject proposal mutation
  const rejectMutation = useMutation({
    mutationFn: (proposalId: string) => aiApi.rejectWorkflowProposal(proposalId),
    onSuccess: () => {
      setIsRejectModalOpen(false);
      queryClient.invalidateQueries({ queryKey: ['ai-workflow-proposals'] });
      if (selectedProposal) {
        setSelectedProposal({
          ...selectedProposal,
          status: 'REJECTED',
        });
      }
    },
    onError: (err: Error) => {
      setErrorMsg(err.message);
      setIsRejectModalOpen(false);
    },
  });

  const handleGenerate = (e: React.FormEvent) => {
    e.preventDefault();
    if (!prompt.trim()) return;
    setErrorMsg(null);
    generateMutation.mutate(prompt.trim());
  };

  // Convert selected proposal nodes to DagVisualizer compatible format
  const dagNodes: WorkflowNodeResponse[] = selectedProposal?.nodes
    ? selectedProposal.nodes.map((n) => ({
        nodeId: n.nodeId,
        taskType: n.taskType,
        payload: n.payload || '{}',
        priority: n.priority || 'NORMAL',
        maxAttempts: n.maxAttempts ?? 3,
        timeout: n.timeout ?? 'PT5M',
      }))
    : [];

  const dagEdges: WorkflowEdge[] = selectedProposal?.edges || [];

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
                backgroundColor: 'rgba(168, 85, 247, 0.15)',
                border: '1px solid rgba(168, 85, 247, 0.4)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: 'var(--accent-purple)',
              }}
            >
              <Bot size={18} />
            </div>
            <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
              AI Workflow Agent
            </h2>
          </div>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginTop: '0.25rem' }}>
            Author deterministic, schema-validated workflow DAG pipelines from natural language requirements.
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

          <LearnAboutPageButton pageId="ai-workflows" />
          <button className="btn btn-secondary btn-sm" onClick={() => refetchProposals()} title="Refresh Proposals">
            <RefreshCw size={14} />
          </button>
        </div>
      </div>

      {errorMsg && <ErrorAlert error={new Error(errorMsg)} onRetry={() => setErrorMsg(null)} />}

      {/* Prompt Input Section */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-default)',
          borderRadius: '0.5rem',
          padding: '1.5rem',
          display: 'flex',
          flexDirection: 'column',
          gap: '1rem',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '0.5rem' }}>
          <label style={{ fontSize: '0.875rem', fontWeight: 600, color: 'var(--text-main)' }}>
            Natural Language Workflow Requirements
          </label>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>Examples:</span>
            {SAMPLE_PROMPTS.map((sample) => (
              <button
                key={sample.label}
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => setPrompt(sample.prompt)}
                style={{ fontSize: '0.7rem', padding: '0.2rem 0.5rem' }}
              >
                {sample.label}
              </button>
            ))}
          </div>
        </div>

        <form onSubmit={handleGenerate} style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
          <div style={{ position: 'relative' }}>
            <textarea
              rows={4}
              className="input-field"
              placeholder="e.g. Download an image from URL, resize to thumbnail, verify with content moderation service, and upload to S3 bucket."
              value={prompt}
              maxLength={4000}
              onChange={(e) => setPrompt(e.target.value)}
              style={{ resize: 'vertical' }}
            />
            <div
              style={{
                position: 'absolute',
                bottom: '0.5rem',
                right: '0.75rem',
                fontSize: '0.7rem',
                color: prompt.length > 3500 ? 'var(--accent-rose)' : 'var(--text-faint)',
                fontFamily: 'var(--font-mono)',
              }}
            >
              {prompt.length} / 4000
            </div>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
              Prompt injection protected &bull; Deterministic DAG validator enforced
            </span>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={!prompt.trim() || generateMutation.isPending}
            >
              <Sparkles size={15} />
              <span>{generateMutation.isPending ? 'Synthesizing DAG...' : 'Generate Proposal'}</span>
            </button>
          </div>
        </form>
      </div>

      {/* Generated Workflow Proposal Preview */}
      {selectedProposal && (
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
          {/* Proposal Header */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.65rem' }}>
                <GitBranch size={18} color="var(--accent-cyan)" />
                <h3 style={{ fontSize: '1.1rem', fontWeight: 600, color: 'var(--text-main)' }}>
                  {selectedProposal.workflowName}
                </h3>
                <span
                  style={{
                    fontSize: '0.7rem',
                    fontFamily: 'var(--font-mono)',
                    padding: '0.15rem 0.45rem',
                    borderRadius: '0.25rem',
                    backgroundColor: selectedProposal.valid ? 'rgba(16, 185, 129, 0.15)' : 'rgba(244, 63, 94, 0.15)',
                    color: selectedProposal.valid ? 'var(--accent-emerald)' : 'var(--accent-rose)',
                    border: selectedProposal.valid ? '1px solid rgba(16, 185, 129, 0.3)' : '1px solid rgba(244, 63, 94, 0.3)',
                    fontWeight: 600,
                  }}
                >
                  {selectedProposal.valid ? 'VALID DAG' : 'INVALID'}
                </span>
                <span
                  style={{
                    fontSize: '0.7rem',
                    fontFamily: 'var(--font-mono)',
                    padding: '0.15rem 0.45rem',
                    borderRadius: '0.25rem',
                    backgroundColor: 'var(--bg-elevated)',
                    color: 'var(--text-muted)',
                    border: '1px solid var(--border-subtle)',
                  }}
                >
                  STATUS: {selectedProposal.status}
                </span>
              </div>
              <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginTop: '0.25rem' }}>
                Proposal ID: <CopyableId id={selectedProposal.proposalId} />
              </p>
            </div>

            {/* Approval Controls */}
            {selectedProposal.status === 'PENDING_REVIEW' && (
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                <button
                  className="btn btn-secondary"
                  onClick={() => setIsRejectModalOpen(true)}
                  disabled={!canMutateTasks || rejectMutation.isPending}
                >
                  <ThumbsDown size={14} />
                  <span>Reject</span>
                </button>
                <button
                  className="btn btn-primary"
                  onClick={() => setIsApproveModalOpen(true)}
                  disabled={!canMutateTasks || !selectedProposal.valid || approveMutation.isPending}
                  title={!canMutateTasks ? 'OPERATOR or ADMIN role required' : ''}
                >
                  <ThumbsUp size={14} />
                  <span>Approve & Materialize Workflow</span>
                </button>
              </div>
            )}
          </div>

          {/* Materialized Workflow Success Alert */}
          {createdWorkflowId && (
            <div
              style={{
                padding: '0.85rem 1rem',
                borderRadius: '0.375rem',
                backgroundColor: 'rgba(16, 185, 129, 0.1)',
                border: '1px solid rgba(16, 185, 129, 0.3)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: 'var(--accent-emerald)', fontSize: '0.875rem' }}>
                <CheckCircle2 size={16} />
                <span>Workflow successfully approved and created in workflow registry as Draft v1!</span>
              </div>
              <Link to={`/workflows/${createdWorkflowId}`} className="btn btn-secondary btn-sm">
                Open Workflow Definition
              </Link>
            </div>
          )}

          {/* Validation Alert */}
          {!selectedProposal.valid ? (
            <div
              style={{
                padding: '0.85rem 1rem',
                borderRadius: '0.375rem',
                backgroundColor: 'rgba(244, 63, 94, 0.1)',
                border: '1px solid rgba(244, 63, 94, 0.3)',
                display: 'flex',
                flexDirection: 'column',
                gap: '0.35rem',
                color: 'var(--accent-rose)',
                fontSize: '0.8125rem',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontWeight: 600 }}>
                <XCircle size={16} />
                <span>Deterministic DAG Validation Errors:</span>
              </div>
              <ul style={{ paddingLeft: '1.5rem', margin: 0 }}>
                {selectedProposal.validationErrors.map((err, idx) => (
                  <li key={idx}>{err}</li>
                ))}
              </ul>
            </div>
          ) : (
            <div
              style={{
                padding: '0.65rem 0.85rem',
                borderRadius: '0.375rem',
                backgroundColor: 'rgba(16, 185, 129, 0.05)',
                border: '1px solid rgba(16, 185, 129, 0.2)',
                display: 'flex',
                alignItems: 'center',
                gap: '0.5rem',
                fontSize: '0.8125rem',
                color: 'var(--accent-emerald)',
              }}
            >
              <CheckCircle2 size={15} />
              <span>Deterministic DAG verification passed: Graph is acyclic with valid dependencies.</span>
            </div>
          )}

          {/* Agent Topology Explanation */}
          {selectedProposal.explanation && (
            <div
              style={{
                padding: '0.85rem 1rem',
                borderRadius: '0.375rem',
                backgroundColor: 'var(--bg-elevated)',
                border: '1px solid var(--border-subtle)',
                fontSize: '0.8125rem',
                color: 'var(--text-muted)',
                lineHeight: 1.5,
              }}
            >
              <div style={{ fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.25rem' }}>
                Topology Reasoning:
              </div>
              {selectedProposal.explanation}
            </div>
          )}

          {/* DAG Visualizer */}
          <div>
            <h4 style={{ fontSize: '0.875rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.5rem' }}>
              DAG Visual Preview
            </h4>
            <DagVisualizer nodes={dagNodes} edges={dagEdges} />
          </div>

          {/* Nodes Table */}
          <div>
            <h4 style={{ fontSize: '0.875rem', fontWeight: 600, color: 'var(--text-main)', marginBottom: '0.5rem' }}>
              Node Specifications ({dagNodes.length})
            </h4>
            <div className="data-table-container">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Node ID</th>
                    <th>Task Type</th>
                    <th>Max Attempts</th>
                    <th>Timeout</th>
                  </tr>
                </thead>
                <tbody>
                  {selectedProposal.nodes.map((node) => (
                    <tr key={node.nodeId}>
                      <td className="font-mono" style={{ fontWeight: 600, color: 'var(--accent-cyan)' }}>
                        {node.nodeId}
                      </td>
                      <td className="font-mono">{node.taskType}</td>
                      <td>{node.maxAttempts ?? 3}</td>
                      <td className="font-mono">{node.timeout ?? 'PT5M'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* Historical Proposals Section */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
        <h3 style={{ fontSize: '1.05rem', fontWeight: 600, color: 'var(--text-main)' }}>
          Recent Workflow Proposals
        </h3>

        {isLoadingProposals ? (
          <LoadingSkeleton rows={4} height="2.5rem" />
        ) : !proposals || proposals.length === 0 ? (
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
            No workflow proposals generated yet. Enter requirements above to create a proposal.
          </div>
        ) : (
          <div className="data-table-container">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Proposal ID</th>
                  <th>Workflow Name</th>
                  <th>Validity</th>
                  <th>Nodes</th>
                  <th>Status</th>
                  <th>Created</th>
                  <th style={{ textAlign: 'right' }}>Action</th>
                </tr>
              </thead>
              <tbody>
                {proposals.map((p) => (
                  <tr key={p.proposalId}>
                    <td>
                      <CopyableId id={p.proposalId} />
                    </td>
                    <td style={{ fontWeight: 600, color: 'var(--text-main)' }}>{p.workflowName}</td>
                    <td>
                      <span
                        style={{
                          fontSize: '0.7rem',
                          fontFamily: 'var(--font-mono)',
                          padding: '0.1rem 0.35rem',
                          borderRadius: '3px',
                          backgroundColor: p.valid ? 'rgba(16, 185, 129, 0.15)' : 'rgba(244, 63, 94, 0.15)',
                          color: p.valid ? 'var(--accent-emerald)' : 'var(--accent-rose)',
                        }}
                      >
                        {p.valid ? 'VALID' : 'INVALID'}
                      </span>
                    </td>
                    <td>{p.nodes?.length || 0} nodes</td>
                    <td>
                      <StatusBadge status={p.status} />
                    </td>
                    <td style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>
                      {formatDate(p.createdAt)}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <button
                        className="btn btn-secondary btn-sm"
                        onClick={() => setSelectedProposal(p)}
                      >
                        Inspect DAG
                      </button>
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
        isOpen={isApproveModalOpen}
        title="Approve & Materialize Workflow"
        description={`Are you sure you want to approve proposal "${selectedProposal?.workflowName}"? This will create a verified workflow definition with Draft Version 1 in the Chronos workflow registry.`}
        confirmLabel="Approve Workflow"
        isLoading={approveMutation.isPending}
        onConfirm={() => selectedProposal && approveMutation.mutate(selectedProposal.proposalId)}
        onClose={() => setIsApproveModalOpen(false)}
      />

      <ConfirmModal
        isOpen={isRejectModalOpen}
        title="Reject Workflow Proposal"
        description={`Are you sure you want to reject proposal "${selectedProposal?.workflowName}"? This proposal will be marked as REJECTED and discarded from deployment consideration.`}
        confirmLabel="Reject Proposal"
        variant="danger"
        isLoading={rejectMutation.isPending}
        onConfirm={() => selectedProposal && rejectMutation.mutate(selectedProposal.proposalId)}
        onClose={() => setIsRejectModalOpen(false)}
      />
    </div>
  );
};
