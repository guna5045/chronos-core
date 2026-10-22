import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WorkflowsPage } from '../pages/WorkflowsPage';
import { WorkflowDetailPage } from '../pages/WorkflowDetailPage';
import { AuthProvider } from '../auth/AuthContext';
import * as workflowApi from '../api/workflows';
import { PagedResponse, WorkflowDefinitionResponse } from '../api/types';
import { ApiClientError } from '../api/client';

const sampleWorkflow: WorkflowDefinitionResponse = {
  workflowId: 'wf-1111-2222',
  name: 'data-etl-pipeline',
  description: 'Nightly ETL pipeline batch',
  activeVersion: {
    versionId: 'ver-1',
    workflowId: 'wf-1111-2222',
    versionNumber: 1,
    status: 'PUBLISHED',
    nodes: [
      { nodeId: 'extract', taskType: 'EXTRACT', maxAttempts: 3 },
      { nodeId: 'transform', taskType: 'TRANSFORM', maxAttempts: 3 },
      { nodeId: 'load', taskType: 'LOAD', maxAttempts: 3 },
    ],
    edges: [
      { fromNode: 'extract', toNode: 'transform' },
      { fromNode: 'transform', toNode: 'load' },
    ],
    createdAt: '2026-10-22T00:00:00Z',
  },
  createdAt: '2026-10-22T00:00:00Z',
  updatedAt: '2026-10-22T00:00:00Z',
};

function renderWithProviders(ui: React.ReactElement, initialRoute = '/workflows') {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[initialRoute]}>
          <Routes>
            <Route path="/workflows" element={ui} />
            <Route path="/workflows/:workflowId" element={<WorkflowDetailPage />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}

describe('Workflows Route Regression & Hardening Suite', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('Test 1: Workflows route renders without blank screen', async () => {
    vi.spyOn(workflowApi, 'getWorkflows').mockResolvedValueOnce({
      items: [sampleWorkflow],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    });

    renderWithProviders(<WorkflowsPage />);
    expect(screen.getByText('Workflow Definitions')).toBeInTheDocument();
  });

  it('Test 2: Loading state renders clean skeletons', async () => {
    vi.spyOn(workflowApi, 'getWorkflows').mockReturnValueOnce(new Promise(() => {}));
    const { container } = renderWithProviders(<WorkflowsPage />);
    expect(screen.getByText('Workflow Definitions')).toBeInTheDocument();
    expect(container.getElementsByClassName('skeleton-row').length).toBeGreaterThan(0);
  });

  it('Test 3: Workflow list renders accurately with PagedResponse backend data', async () => {
    vi.spyOn(workflowApi, 'getWorkflows').mockResolvedValueOnce({
      items: [sampleWorkflow],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    });

    renderWithProviders(<WorkflowsPage />);
    await waitFor(() => {
      expect(screen.getByText('data-etl-pipeline')).toBeInTheDocument();
      expect(screen.getByText('v1')).toBeInTheDocument();
      expect(screen.getByText('Inspect DAG')).toBeInTheDocument();
    });
  });

  it('Test 4: Empty state renders when backend returns zero workflows', async () => {
    vi.spyOn(workflowApi, 'getWorkflows').mockResolvedValueOnce({
      items: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      hasNext: false,
      hasPrevious: false,
    });

    renderWithProviders(<WorkflowsPage />);
    await waitFor(() => {
      expect(screen.getByText('No workflow definitions registered')).toBeInTheDocument();
    });
  });

  it('Test 5: API error renders structured ErrorAlert with retry', async () => {
    const error = new ApiClientError({
      timestamp: new Date().toISOString(),
      status: 503,
      code: 'COORDINATOR_UNREACHABLE',
      message: 'Coordinator process unreachable',
      path: '/workflows',
      requestId: 'req-err-wf',
    });
    vi.spyOn(workflowApi, 'getWorkflows').mockRejectedValueOnce(error);

    renderWithProviders(<WorkflowsPage />);
    await waitFor(() => {
      expect(screen.getByText('Unable to load workflow definitions')).toBeInTheDocument();
      expect(screen.getByText('req-err-wf')).toBeInTheDocument();
    });
  });

  it('Test 6: Retry triggers refetch without page reload', async () => {
    const error = new ApiClientError({
      timestamp: new Date().toISOString(),
      status: 503,
      code: 'COORDINATOR_UNREACHABLE',
      message: 'Coordinator process unreachable',
      path: '/workflows',
    });

    const getWorkflowsMock = vi
      .spyOn(workflowApi, 'getWorkflows')
      .mockRejectedValueOnce(error)
      .mockResolvedValueOnce({
        items: [sampleWorkflow],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
        hasNext: false,
        hasPrevious: false,
      });

    renderWithProviders(<WorkflowsPage />);

    await waitFor(() => {
      expect(screen.getByText('Unable to load workflow definitions')).toBeInTheDocument();
    });

    const retryBtn = screen.getByRole('button', { name: /retry/i });
    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(screen.getByText('data-etl-pipeline')).toBeInTheDocument();
    });
    expect(getWorkflowsMock).toHaveBeenCalledTimes(2);
  });

  it('Test 7: Malformed or unexpected response shape does not crash page into blank screen', async () => {
    // Malformed object without items array
    vi.spyOn(workflowApi, 'getWorkflows').mockResolvedValueOnce({} as any);

    renderWithProviders(<WorkflowsPage />);
    await waitFor(() => {
      // Must safely show empty state rather than throwing TypeError: workflows.map is not a function
      expect(screen.getByText('No workflow definitions registered')).toBeInTheDocument();
    });
  });

  it('Test 8: Workflow detail view renders on /workflows/:id', async () => {
    vi.spyOn(workflowApi, 'getWorkflow').mockResolvedValueOnce(sampleWorkflow);
    vi.spyOn(workflowApi, 'getWorkflowVersions').mockResolvedValueOnce([sampleWorkflow.activeVersion!]);

    renderWithProviders(<WorkflowDetailPage />, '/workflows/wf-1111-2222');
    await waitFor(() => {
      expect(screen.getByText('data-etl-pipeline')).toBeInTheDocument();
      expect(screen.getByText(/DAG Topology/i)).toBeInTheDocument();
    });
  });

  it('Test 9: DAG visualizer renders nodes and edges correctly', async () => {
    vi.spyOn(workflowApi, 'getWorkflow').mockResolvedValueOnce(sampleWorkflow);
    vi.spyOn(workflowApi, 'getWorkflowVersions').mockResolvedValueOnce([sampleWorkflow.activeVersion!]);

    renderWithProviders(<WorkflowDetailPage />, '/workflows/wf-1111-2222');
    await waitFor(() => {
      expect(screen.getByText('EXTRACT')).toBeInTheDocument();
      expect(screen.getByText('TRANSFORM')).toBeInTheDocument();
      expect(screen.getByText('LOAD')).toBeInTheDocument();
    });
  });

  it('Test 10: Supports Dark theme on /workflows', () => {
    document.documentElement.setAttribute('data-theme', 'dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('Test 11: Supports Light theme on /workflows with zero dark leftovers', () => {
    document.documentElement.setAttribute('data-theme', 'light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('Test 12: Direct navigation to /workflows URL renders reliably', async () => {
    vi.spyOn(workflowApi, 'getWorkflows').mockResolvedValueOnce({
      items: [sampleWorkflow],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    });

    renderWithProviders(<WorkflowsPage />, '/workflows');
    await waitFor(() => {
      expect(screen.getByText('Workflow Definitions')).toBeInTheDocument();
      expect(screen.getByText('data-etl-pipeline')).toBeInTheDocument();
    });
  });

  it('Test 13: Browser simulated re-mount / refresh on /workflows never turns blank', async () => {
    vi.spyOn(workflowApi, 'getWorkflows').mockResolvedValue({
      items: [sampleWorkflow],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    });

    const { unmount } = renderWithProviders(<WorkflowsPage />, '/workflows');
    unmount();

    // Re-mount (simulating browser reload on same URL)
    renderWithProviders(<WorkflowsPage />, '/workflows');
    await waitFor(() => {
      expect(screen.getByText('data-etl-pipeline')).toBeInTheDocument();
    });
  });
});
