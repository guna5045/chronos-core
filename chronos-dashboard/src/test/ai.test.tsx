import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '../auth/AuthContext';
import { AiWorkflowsPage } from '../pages/AiWorkflowsPage';
import { AiOperationsPage } from '../pages/AiOperationsPage';
import { aiApi } from '../api/ai';

// Mock aiApi
vi.mock('../api/ai', () => ({
  aiApi: {
    getStatus: vi.fn(),
    generateWorkflowProposal: vi.fn(),
    listProposals: vi.fn(),
    getProposal: vi.fn(),
    approveWorkflowProposal: vi.fn(),
    rejectWorkflowProposal: vi.fn(),
    analyzeIncident: vi.fn(),
    listActions: vi.fn(),
    getAction: vi.fn(),
    approveAction: vi.fn(),
    rejectAction: vi.fn(),
  },
}));

function renderWithProviders(ui: React.ReactElement, initialRole = 'OPERATOR') {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });

  localStorage.setItem('chronos_auth_token', 'test-jwt-token');
  localStorage.setItem(
    'chronos_auth_user',
    JSON.stringify({ subject: 'test-user', roles: [initialRole], tenantId: 'tenant-alpha' })
  );

  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>{ui}</BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}

describe('AI Workflow & Operations Control Plane Pages', () => {
  beforeEach(() => {
    vi.clearAllMocks();

    vi.mocked(aiApi.getStatus).mockResolvedValue({
      enabled: true,
      provider: 'OPENAI_COMPATIBLE',
      model: 'gpt-4o-mini',
      available: true,
    });

    vi.mocked(aiApi.listProposals).mockResolvedValue([]);
    vi.mocked(aiApi.listActions).mockResolvedValue([]);
  });

  it('renders AI Workflows page with engine status and sample prompt triggers', async () => {
    renderWithProviders(<AiWorkflowsPage />);

    expect(screen.getByText('AI Workflow Agent')).toBeInTheDocument();
    expect(
      screen.getByText(/Author deterministic, schema-validated workflow DAG pipelines/i)
    ).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText(/OPENAI_COMPATIBLE \(gpt-4o-mini\)/i)).toBeInTheDocument();
    });

    // Check sample buttons
    expect(screen.getByText('Image Pipeline')).toBeInTheDocument();
    expect(screen.getByText('ETL Pipeline')).toBeInTheDocument();
    expect(screen.getByText('ML Model Inference')).toBeInTheDocument();

    // Clicking sample button populates textarea
    fireEvent.click(screen.getByText('Image Pipeline'));
    const textarea = screen.getByPlaceholderText(/Download an image from URL/i) as HTMLTextAreaElement;
    expect(textarea.value).toContain('Download an image');
  });

  it('generates and displays validated workflow proposal with DAG preview', async () => {
    vi.mocked(aiApi.generateWorkflowProposal).mockResolvedValue({
      proposalId: 'prop-12345',
      tenantId: 'tenant-alpha',
      workflowName: 'image-processing-pipeline',
      description: 'Image processing flow',
      nodes: [
        { nodeId: 'download', taskType: 'DOWNLOAD', maxAttempts: 3, timeout: 'PT5M' },
        { nodeId: 'resize', taskType: 'RESIZE', maxAttempts: 3, timeout: 'PT5M' },
        { nodeId: 'upload', taskType: 'UPLOAD', maxAttempts: 3, timeout: 'PT5M' },
      ],
      edges: [
        { sourceNodeId: 'download', targetNodeId: 'resize' },
        { sourceNodeId: 'resize', targetNodeId: 'upload' },
      ],
      explanation: 'Sequential download -> resize -> upload',
      valid: true,
      validationErrors: [],
      status: 'PENDING_REVIEW',
      createdAt: '2026-10-22T00:00:00Z',
      expiresAt: '2026-10-22T00:30:00Z',
    });

    renderWithProviders(<AiWorkflowsPage />);

    const textarea = screen.getByPlaceholderText(/Download an image from URL/i);
    fireEvent.change(textarea, { target: { value: 'Download image and resize then upload' } });

    const generateBtn = screen.getByText('Generate Proposal');
    fireEvent.click(generateBtn);

    await waitFor(() => {
      expect(screen.getByText('image-processing-pipeline')).toBeInTheDocument();
      expect(screen.getByText('VALID DAG')).toBeInTheDocument();
      expect(screen.getByText(/Sequential download -> resize -> upload/i)).toBeInTheDocument();
      expect(screen.getByText('Approve & Materialize Workflow')).toBeInTheDocument();
    });
  });

  it('renders AI Operations page and conducts telemetry incident analysis', async () => {
    vi.mocked(aiApi.analyzeIncident).mockResolvedValue({
      analysisId: 'analysis-999',
      scope: 'CLUSTER',
      targetId: 'cluster',
      severity: 'HIGH',
      summary: 'Elevated task retry volume and worker timeout alerts detected.',
      likelyCauses: [
        'Worker worker-02 missed 3 consecutive heartbeats',
        'Downstream database lock contention causing task timeouts',
      ],
      evidence: [
        {
          source: 'worker',
          identifier: 'worker-02',
          summary: 'Worker in SUSPECT state after heartbeat missed',
          status: 'SUSPECTED',
        },
      ],
      recommendations: [
        {
          actionId: 'act-101',
          tenantId: 'tenant-alpha',
          actionType: 'CANCEL_TASK',
          targetId: 'task-02',
          title: 'Drain unresponsive worker node',
          reason: 'Worker missed heartbeats; prevent assigning new tasks',
          risk: 'MEDIUM',
          evidence: [],
          status: 'PENDING_APPROVAL',
          expiresAt: '2026-10-22T01:00:00Z',
          createdAt: '2026-10-22T00:00:00Z',
        },
      ],
      analyzedAt: '2026-10-22T00:00:00Z',
    });

    renderWithProviders(<AiOperationsPage />);

    expect(screen.getByText('AI Operations Agent')).toBeInTheDocument();
    expect(
      screen.getByText(/Telemetry Correlation & Incident Analysis/i)
    ).toBeInTheDocument();

    const analyzeBtn = screen.getByText('Run Incident Diagnosis');
    fireEvent.click(analyzeBtn);

    await waitFor(() => {
      expect(screen.getByText('HIGH SEVERITY')).toBeInTheDocument();
      expect(
        screen.getByText('Elevated task retry volume and worker timeout alerts detected.')
      ).toBeInTheDocument();
      expect(
        screen.getByText('Worker worker-02 missed 3 consecutive heartbeats')
      ).toBeInTheDocument();
      expect(screen.getByText('Drain unresponsive worker node')).toBeInTheDocument();
      expect(screen.getByText('RISK: MEDIUM')).toBeInTheDocument();
    });
  });
});
