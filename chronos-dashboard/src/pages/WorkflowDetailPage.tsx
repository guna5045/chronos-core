import React, { useState } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createWorkflowVersion,
  deprecateWorkflowVersion,
  executeWorkflow,
  getWorkflow,
  getWorkflowVersions,
  publishWorkflowVersion,
} from '../api/workflows';
import { CreateWorkflowVersionRequest } from '../api/types';
import { DagVisualizer } from '../components/workflow/DagVisualizer';
import { StatusBadge } from '../components/common/StatusBadge';
import { CopyableId } from '../components/common/CopyableId';
import { ConfirmModal } from '../components/common/ConfirmModal';
import { LoadingSkeleton } from '../components/common/LoadingSkeleton';
import { ErrorAlert } from '../components/common/ErrorAlert';
import { formatDate } from '../utils/formatters';
import { useAuth } from '../auth/AuthContext';
import {
  ArrowLeft,
  CheckCircle,
  GitBranch,
  Play,
  Plus,
  Slash,
  X,
} from 'lucide-react';
import { ApiClientError } from '../api/client';

export const WorkflowDetailPage: React.FC = () => {
  const { workflowId } = useParams<{ workflowId: string }>();
  const { canMutateTasks, canManageWorkflowVersions, user } = useAuth();
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const [selectedVersionId, setSelectedVersionId] = useState<string | null>(null);
  const [isExecuteOpen, setIsExecuteOpen] = useState(false);
  const executeTenantId = user?.tenantId || 'tenant-alpha';
  const [isDraftModalOpen, setIsDraftModalOpen] = useState(false);

  // Workflow query
  const workflowQuery = useQuery({
    queryKey: ['workflow', workflowId],
    queryFn: () => getWorkflow(workflowId!),
    enabled: !!workflowId,
  });

  // Versions query
  const versionsQuery = useQuery({
    queryKey: ['workflowVersions', workflowId],
    queryFn: () => getWorkflowVersions(workflowId!),
    enabled: !!workflowId,
    refetchInterval: 10000,
  });

  const publishMutation = useMutation({
    mutationFn: (vNum: number) => publishWorkflowVersion(workflowId!, vNum),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflowVersions', workflowId] });
      queryClient.invalidateQueries({ queryKey: ['workflow', workflowId] });
    },
  });

  const deprecateMutation = useMutation({
    mutationFn: (vNum: number) => deprecateWorkflowVersion(workflowId!, vNum),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflowVersions', workflowId] });
      queryClient.invalidateQueries({ queryKey: ['workflow', workflowId] });
    },
  });

  const executeMutation = useMutation({
    mutationFn: (versionId?: string) =>
      executeWorkflow(workflowId!, {
        versionId,
        tenantId: executeTenantId.trim(),
      }),
    onSuccess: (exec) => {
      setIsExecuteOpen(false);
      navigate(`/workflow-executions/${exec.executionId}`);
    },
  });

  // State for authoring a draft version
  const [draftNodesJson, setDraftNodesJson] = useState(
    JSON.stringify(
      [
        { nodeId: 'extract', taskType: 'DATA_EXTRACT', maxAttempts: 3 },
        { nodeId: 'transform', taskType: 'DATA_TRANSFORM', maxAttempts: 3 },
        { nodeId: 'load', taskType: 'DATA_LOAD', maxAttempts: 3 },
      ],
      null,
      2
    )
  );
  const [draftEdgesJson, setDraftEdgesJson] = useState(
    JSON.stringify(
      [
        { sourceNodeId: 'extract', targetNodeId: 'transform' },
        { sourceNodeId: 'transform', targetNodeId: 'load' },
      ],
      null,
      2
    )
  );
  const [draftError, setDraftError] = useState<string | null>(null);

  const draftMutation = useMutation({
    mutationFn: (body: CreateWorkflowVersionRequest) =>
      createWorkflowVersion(workflowId!, body),
    onSuccess: (newVersion) => {
      queryClient.invalidateQueries({ queryKey: ['workflowVersions', workflowId] });
      setIsDraftModalOpen(false);
      setSelectedVersionId(newVersion.versionId);
    },
    onError: (err) => {
      if (err instanceof ApiClientError) {
        setDraftError(`${err.code}: ${err.message}`);
      } else if (err instanceof Error) {
        setDraftError(err.message);
      }
    },
  });

  const handleCreateDraft = (e: React.FormEvent) => {
    e.preventDefault();
    setDraftError(null);
    try {
      const nodes = JSON.parse(draftNodesJson);
      const edges = JSON.parse(draftEdgesJson);
      draftMutation.mutate({ nodes, edges });
    } catch {
      setDraftError('Invalid JSON format for nodes or edges.');
    }
  };

  const isLoading = workflowQuery.isLoading || versionsQuery.isLoading;

  if (isLoading) {
    return <LoadingSkeleton rows={6} height="3rem" />;
  }

  if (workflowQuery.isError || !workflowQuery.data) {
    return <ErrorAlert error={workflowQuery.error || new Error('Workflow not found')} />;
  }

  const workflow = workflowQuery.data;
  const versions = versionsQuery.data || [];

  // Determine active displayed version
  const activeVersion =
    versions.find((v) => v.versionId === selectedVersionId) ||
    versions.find((v) => v.status === 'PUBLISHED') ||
    versions[0] ||
    null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* Top Header */}
      <div>
        <Link
          to="/workflows"
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
          <span>Workflow Definitions</span>
        </Link>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '1rem' }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
              <h2 style={{ fontSize: '1.35rem', fontWeight: 700, color: 'var(--text-main)', letterSpacing: '0.01em' }}>
                {workflow.name}
              </h2>
              <CopyableId id={workflow.workflowId} />
            </div>
            {workflow.description && (
              <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginTop: '0.25rem' }}>
                {workflow.description}
              </p>
            )}
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            {canManageWorkflowVersions && (
              <button className="btn btn-secondary" onClick={() => setIsDraftModalOpen(true)}>
                <Plus size={14} />
                <span>Draft New Version</span>
              </button>
            )}

            {canMutateTasks && activeVersion?.status === 'PUBLISHED' && (
              <button className="btn btn-primary" onClick={() => setIsExecuteOpen(true)}>
                <Play size={14} />
                <span>Execute Workflow</span>
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Versions Tabs & Status Bar */}
      <div
        style={{
          backgroundColor: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: '0.5rem',
          padding: '0.75rem 1.25rem',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          gap: '1rem',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
          <span style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase' }}>
            Versions:
          </span>
          {versions.length === 0 ? (
            <span style={{ fontSize: '0.8125rem', color: 'var(--text-faint)' }}>No versions drafted yet</span>
          ) : (
            versions.map((v) => {
              const isSelected = activeVersion?.versionId === v.versionId;
              return (
                <button
                  key={v.versionId}
                  onClick={() => setSelectedVersionId(v.versionId)}
                  style={{
                    padding: '0.3rem 0.65rem',
                    borderRadius: '0.25rem',
                    border: isSelected ? '1px solid var(--accent-cyan)' : '1px solid var(--border-default)',
                    backgroundColor: isSelected ? 'rgba(56, 189, 248, 0.12)' : 'var(--bg-elevated)',
                    color: isSelected ? 'var(--accent-cyan)' : 'var(--text-muted)',
                    fontFamily: 'var(--font-mono)',
                    fontSize: '0.75rem',
                    fontWeight: 600,
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '0.35rem',
                  }}
                >
                  <span>v{v.versionNumber}</span>
                  <StatusBadge status={v.status} type="version" />
                </button>
              );
            })
          )}
        </div>

        {activeVersion && canManageWorkflowVersions && (
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            {activeVersion.status === 'DRAFT' && (
              <button
                className="btn btn-success btn-sm"
                onClick={() => publishMutation.mutate(activeVersion.versionNumber)}
                disabled={publishMutation.isPending}
              >
                <CheckCircle size={12} />
                <span>Publish Version (Immutable)</span>
              </button>
            )}

            {activeVersion.status === 'PUBLISHED' && (
              <button
                className="btn btn-danger btn-sm"
                onClick={() => deprecateMutation.mutate(activeVersion.versionNumber)}
                disabled={deprecateMutation.isPending}
              >
                <Slash size={12} />
                <span>Deprecate Version</span>
              </button>
            )}
          </div>
        )}
      </div>

      {/* DAG Visualizer Section */}
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
              DAG Topology &mdash; {activeVersion ? `Version #${activeVersion.versionNumber}` : 'Empty'}
            </h3>
            <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
              Deterministic directed dependency graph • Click node to inspect definition
            </p>
          </div>
          {activeVersion?.publishedAt && (
            <span style={{ fontSize: '0.75rem', color: 'var(--text-faint)' }}>
              Published: {formatDate(activeVersion.publishedAt)}
            </span>
          )}
        </div>

        {activeVersion ? (
          <DagVisualizer nodes={activeVersion.nodes} edges={activeVersion.edges} />
        ) : (
          <div style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-muted)' }}>
            No versions created yet. Click &ldquo;Draft New Version&rdquo; to define nodes and edges.
          </div>
        )}
      </div>

      {/* Execute Workflow Modal */}
      {isExecuteOpen && (
        <ConfirmModal
          isOpen={isExecuteOpen}
          title="Instantiate Workflow Execution"
          description={`Launch an asynchronous workflow execution for version v${activeVersion?.versionNumber} under tenant ${executeTenantId}. Root tasks will be submitted to the scheduler.`}
          confirmLabel="Execute Workflow"
          isLoading={executeMutation.isPending}
          onConfirm={() => executeMutation.mutate(activeVersion?.versionId)}
          onClose={() => setIsExecuteOpen(false)}
        />
      )}

      {/* Draft Version Modal */}
      {isDraftModalOpen && (
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
          onClick={() => setIsDraftModalOpen(false)}
        >
          <div
            style={{
              backgroundColor: 'var(--bg-surface)',
              border: '1px solid var(--border-default)',
              borderRadius: '0.5rem',
              maxWidth: '620px',
              width: '100%',
              padding: '1.5rem',
              maxHeight: '90vh',
              overflowY: 'auto',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1.25rem' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <GitBranch size={18} color="var(--accent-cyan)" />
                <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-main)' }}>
                  Draft New Workflow Version
                </h3>
              </div>
              <button
                onClick={() => setIsDraftModalOpen(false)}
                style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}
              >
                <X size={18} />
              </button>
            </div>

            {draftError && (
              <div style={{ color: 'var(--accent-rose)', fontSize: '0.8125rem', marginBottom: '1rem' }}>
                {draftError}
              </div>
            )}

            <form onSubmit={handleCreateDraft} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Node Definitions (JSON Array) *
                </label>
                <textarea
                  required
                  rows={6}
                  className="input-field font-mono"
                  style={{ resize: 'vertical', fontSize: '0.75rem' }}
                  value={draftNodesJson}
                  onChange={(e) => setDraftNodesJson(e.target.value)}
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                  Directed Edges (JSON Array) *
                </label>
                <textarea
                  required
                  rows={4}
                  className="input-field font-mono"
                  style={{ resize: 'vertical', fontSize: '0.75rem' }}
                  value={draftEdgesJson}
                  onChange={(e) => setDraftEdgesJson(e.target.value)}
                />
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem', marginTop: '0.5rem' }}>
                <button type="button" className="btn btn-secondary" onClick={() => setIsDraftModalOpen(false)}>
                  Cancel
                </button>
                <button type="submit" className="btn btn-primary" disabled={draftMutation.isPending}>
                  {draftMutation.isPending ? 'Validating & Persisting...' : 'Create Draft Version'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
