import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DagVisualizer } from '../components/workflow/DagVisualizer';
import { WorkflowEdge, WorkflowExecutionNodeState, WorkflowNodeResponse } from '../api/types';

describe('Workflow DAG Visualizer Component', () => {
  const mockNodes: WorkflowNodeResponse[] = [
    { nodeId: 'download', taskType: 'DOWNLOAD', payload: '{}', priority: 'NORMAL', timeout: null, maxAttempts: 3 },
    { nodeId: 'resize', taskType: 'RESIZE', payload: '{}', priority: 'NORMAL', timeout: null, maxAttempts: 3 },
    { nodeId: 'filter', taskType: 'FILTER', payload: '{}', priority: 'NORMAL', timeout: null, maxAttempts: 3 },
    { nodeId: 'upload', taskType: 'UPLOAD', payload: '{}', priority: 'NORMAL', timeout: null, maxAttempts: 3 },
  ];

  const mockEdges: WorkflowEdge[] = [
    { sourceNodeId: 'download', targetNodeId: 'resize' },
    { sourceNodeId: 'download', targetNodeId: 'filter' },
    { sourceNodeId: 'resize', targetNodeId: 'upload' },
    { sourceNodeId: 'filter', targetNodeId: 'upload' },
  ];

  it('renders all nodes in the DAG', () => {
    render(<DagVisualizer nodes={mockNodes} edges={mockEdges} />);

    expect(screen.getByText('download')).toBeInTheDocument();
    expect(screen.getByText('resize')).toBeInTheDocument();
    expect(screen.getByText('filter')).toBeInTheDocument();
    expect(screen.getByText('upload')).toBeInTheDocument();
  });

  it('renders task types for each node', () => {
    render(<DagVisualizer nodes={mockNodes} edges={mockEdges} />);

    expect(screen.getByText('DOWNLOAD')).toBeInTheDocument();
    expect(screen.getByText('RESIZE')).toBeInTheDocument();
    expect(screen.getByText('FILTER')).toBeInTheDocument();
    expect(screen.getByText('UPLOAD')).toBeInTheDocument();
  });

  it('highlights node states when execution telemetry is provided', () => {
    const mockExecutionNodes: WorkflowExecutionNodeState[] = [
      { nodeId: 'download', status: 'SUCCEEDED', taskId: 'task-1', startedAt: null, completedAt: null, failureReason: null },
      { nodeId: 'resize', status: 'RUNNING', taskId: 'task-2', startedAt: null, completedAt: null, failureReason: null },
      { nodeId: 'filter', status: 'FAILED', taskId: 'task-3', startedAt: null, completedAt: null, failureReason: 'Worker crashed' },
      { nodeId: 'upload', status: 'PENDING', taskId: null, startedAt: null, completedAt: null, failureReason: null },
    ];

    render(
      <DagVisualizer
        nodes={mockNodes}
        edges={mockEdges}
        executionNodes={mockExecutionNodes}
      />
    );

    expect(screen.getByText('SUCCEEDED')).toBeInTheDocument();
    expect(screen.getByText('RUNNING')).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();
    expect(screen.getByText('PENDING')).toBeInTheDocument();
  });

  it('selects a node on click and displays execution diagnostics', () => {
    const handleNodeClick = vi.fn();
    const mockExecutionNodes: WorkflowExecutionNodeState[] = [
      {
        nodeId: 'filter',
        status: 'FAILED',
        taskId: '6a42cd3e-34de-4c61-b256-2dc48feccc21',
        startedAt: '2026-10-21T18:10:00Z',
        completedAt: '2026-10-21T18:10:05Z',
        failureReason: 'Out of memory during image filtering',
      },
    ];

    render(
      <DagVisualizer
        nodes={mockNodes}
        edges={mockEdges}
        executionNodes={mockExecutionNodes}
        onNodeClick={handleNodeClick}
      />
    );

    // Click filter node
    fireEvent.click(screen.getByText('filter'));
    expect(handleNodeClick).toHaveBeenCalledWith('filter');

    // Details drawer should be visible
    expect(screen.getByText('Out of memory during image filtering')).toBeInTheDocument();
  });
});
