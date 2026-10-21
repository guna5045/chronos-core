import React, { useMemo, useState } from 'react';
import { WorkflowEdge, WorkflowExecutionNodeState, WorkflowNodeResponse } from '../../api/types';
import { StatusBadge } from '../common/StatusBadge';
import { CopyableId } from '../common/CopyableId';
import { formatDate } from '../../utils/formatters';

interface DagVisualizerProps {
  nodes: WorkflowNodeResponse[];
  edges: WorkflowEdge[];
  executionNodes?: WorkflowExecutionNodeState[];
  onNodeClick?: (nodeId: string) => void;
}

interface LayoutNode {
  nodeId: string;
  taskType: string;
  rank: number;
  x: number;
  y: number;
  width: number;
  height: number;
  status?: string;
  execution?: WorkflowExecutionNodeState;
  definition: WorkflowNodeResponse;
}

export const DagVisualizer: React.FC<DagVisualizerProps> = ({
  nodes,
  edges,
  executionNodes = [],
  onNodeClick,
}) => {
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);

  const executionNodeMap = useMemo(() => {
    const map = new Map<string, WorkflowExecutionNodeState>();
    executionNodes.forEach((en) => map.set(en.nodeId, en));
    return map;
  }, [executionNodes]);

  // Compute topological rank (longest path from any root node)
  const layout = useMemo(() => {
    if (!nodes || nodes.length === 0) {
      return { layoutNodes: [], layoutEdges: [], width: 400, height: 200 };
    }

    const inDegree = new Map<string, number>();
    const adj = new Map<string, string[]>();
    nodes.forEach((n) => {
      inDegree.set(n.nodeId, 0);
      adj.set(n.nodeId, []);
    });

    edges.forEach((e) => {
      if (adj.has(e.sourceNodeId) && inDegree.has(e.targetNodeId)) {
        adj.get(e.sourceNodeId)!.push(e.targetNodeId);
        inDegree.set(e.targetNodeId, (inDegree.get(e.targetNodeId) || 0) + 1);
      }
    });

    // BFS / Kahn to assign ranks
    const rankMap = new Map<string, number>();
    const queue: string[] = [];

    nodes.forEach((n) => {
      if ((inDegree.get(n.nodeId) || 0) === 0) {
        queue.push(n.nodeId);
        rankMap.set(n.nodeId, 0);
      }
    });

    while (queue.length > 0) {
      const curr = queue.shift()!;
      const currRank = rankMap.get(curr) || 0;
      const neighbors = adj.get(curr) || [];

      neighbors.forEach((nbr) => {
        const nextRank = Math.max(rankMap.get(nbr) || 0, currRank + 1);
        rankMap.set(nbr, nextRank);
        queue.push(nbr);
      });
    }

    // Group nodes by rank
    const rankBuckets = new Map<number, WorkflowNodeResponse[]>();
    nodes.forEach((n) => {
      const r = rankMap.get(n.nodeId) || 0;
      if (!rankBuckets.has(r)) rankBuckets.set(r, []);
      rankBuckets.get(r)!.push(n);
    });

    const NODE_WIDTH = 190;
    const NODE_HEIGHT = 70;
    const HORIZONTAL_GAP = 120;
    const VERTICAL_GAP = 40;
    const PADDING = 40;

    let maxRank = 0;
    let maxNodesInRank = 0;

    rankBuckets.forEach((bucketNodes, r) => {
      if (r > maxRank) maxRank = r;
      if (bucketNodes.length > maxNodesInRank) maxNodesInRank = bucketNodes.length;
    });

    const totalWidth = PADDING * 2 + (maxRank + 1) * NODE_WIDTH + maxRank * HORIZONTAL_GAP;
    const totalHeight = Math.max(300, PADDING * 2 + maxNodesInRank * NODE_HEIGHT + (maxNodesInRank - 1) * VERTICAL_GAP);

    const layoutNodesMap = new Map<string, LayoutNode>();
    const layoutNodes: LayoutNode[] = [];

    rankBuckets.forEach((bucketNodes, r) => {
      const bucketHeight = bucketNodes.length * NODE_HEIGHT + (bucketNodes.length - 1) * VERTICAL_GAP;
      const startY = (totalHeight - bucketHeight) / 2;

      bucketNodes.forEach((node, idx) => {
        const x = PADDING + r * (NODE_WIDTH + HORIZONTAL_GAP);
        const y = startY + idx * (NODE_HEIGHT + VERTICAL_GAP);
        const execState = executionNodeMap.get(node.nodeId);

        const lNode: LayoutNode = {
          nodeId: node.nodeId,
          taskType: node.taskType,
          rank: r,
          x,
          y,
          width: NODE_WIDTH,
          height: NODE_HEIGHT,
          status: execState?.status,
          execution: execState,
          definition: node,
        };
        layoutNodesMap.set(node.nodeId, lNode);
        layoutNodes.push(lNode);
      });
    });

    const layoutEdges = edges
      .map((e) => {
        const source = layoutNodesMap.get(e.sourceNodeId);
        const target = layoutNodesMap.get(e.targetNodeId);
        if (!source || !target) return null;

        const startX = source.x + source.width;
        const startY = source.y + source.height / 2;
        const endX = target.x;
        const endY = target.y + target.height / 2;
        const dx = (endX - startX) / 2;

        const path = `M ${startX} ${startY} C ${startX + dx} ${startY}, ${endX - dx} ${endY}, ${endX} ${endY}`;
        return {
          id: `${e.sourceNodeId}->${e.targetNodeId}`,
          path,
          sourceStatus: source.status,
          targetStatus: target.status,
        };
      })
      .filter((e): e is NonNullable<typeof e> => e !== null);

    return {
      layoutNodes,
      layoutEdges,
      width: Math.max(600, totalWidth),
      height: totalHeight,
    };
  }, [nodes, edges, executionNodeMap]);

  const selectedNode = useMemo(() => {
    if (!selectedNodeId) return null;
    return layout.layoutNodes.find((n) => n.nodeId === selectedNodeId) || null;
  }, [selectedNodeId, layout.layoutNodes]);

  const handleNodeClick = (nodeId: string) => {
    setSelectedNodeId((prev) => (prev === nodeId ? null : nodeId));
    if (onNodeClick) onNodeClick(nodeId);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', width: '100%' }}>
      <div
        style={{
          width: '100%',
          overflowX: 'auto',
          backgroundColor: 'var(--bg-canvas)',
          border: '1px solid var(--border-default)',
          borderRadius: '0.5rem',
          padding: '1rem',
          position: 'relative',
        }}
      >
        <svg
          width={layout.width}
          height={layout.height}
          style={{ display: 'block', margin: '0 auto' }}
        >
          <defs>
            <marker
              id="dag-arrow"
              viewBox="0 0 10 10"
              refX="8"
              refY="5"
              markerWidth="6"
              markerHeight="6"
              orient="auto-start-reverse"
            >
              <path d="M 0 1 L 10 5 L 0 9 z" fill="var(--border-default)" />
            </marker>
            <marker
              id="dag-arrow-active"
              viewBox="0 0 10 10"
              refX="8"
              refY="5"
              markerWidth="6"
              markerHeight="6"
              orient="auto-start-reverse"
            >
              <path d="M 0 1 L 10 5 L 0 9 z" fill="var(--accent-cyan)" />
            </marker>
          </defs>

          {/* Render directed edges */}
          {layout.layoutEdges.map((e) => {
            const isCompleted = e.sourceStatus === 'SUCCEEDED';
            return (
              <path
                key={e.id}
                d={e.path}
                fill="none"
                stroke={isCompleted ? 'var(--accent-emerald)' : 'var(--border-default)'}
                strokeWidth={isCompleted ? 2 : 1.5}
                strokeDasharray={e.sourceStatus === 'RUNNING' ? '4 4' : undefined}
                markerEnd={isCompleted ? 'url(#dag-arrow-active)' : 'url(#dag-arrow)'}
                opacity={0.8}
              />
            );
          })}

          {/* Render DAG Nodes */}
          {layout.layoutNodes.map((n) => {
            const isSelected = selectedNodeId === n.nodeId;
            let borderColor = 'var(--border-default)';
            let bgColor = 'var(--bg-surface)';

            if (n.status === 'SUCCEEDED') {
              borderColor = 'rgba(16, 185, 129, 0.4)';
              bgColor = 'rgba(16, 185, 129, 0.05)';
            } else if (n.status === 'RUNNING') {
              borderColor = 'var(--accent-cyan)';
              bgColor = 'rgba(2, 132, 199, 0.1)';
            } else if (n.status === 'FAILED') {
              borderColor = 'rgba(244, 63, 94, 0.5)';
              bgColor = 'rgba(244, 63, 94, 0.08)';
            }

            return (
              <g
                key={n.nodeId}
                transform={`translate(${n.x}, ${n.y})`}
                onClick={() => handleNodeClick(n.nodeId)}
                style={{ cursor: 'pointer' }}
              >
                <rect
                  width={n.width}
                  height={n.height}
                  rx={6}
                  fill={bgColor}
                  stroke={isSelected ? 'var(--accent-cyan)' : borderColor}
                  strokeWidth={isSelected ? 2 : 1}
                />
                <text
                  x={12}
                  y={24}
                  fill="var(--text-main)"
                  fontSize={12}
                  fontWeight={600}
                  fontFamily="var(--font-mono)"
                >
                  {n.nodeId}
                </text>
                <text
                  x={12}
                  y={42}
                  fill="var(--text-muted)"
                  fontSize={10}
                  fontFamily="var(--font-mono)"
                >
                  {n.taskType}
                </text>
                {n.status && (
                  <foreignObject x={n.width - 95} y={12} width={85} height={26}>
                    <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                      <StatusBadge status={n.status} />
                    </div>
                  </foreignObject>
                )}
              </g>
            );
          })}
        </svg>
      </div>

      {/* Selected Node Details Drawer */}
      {selectedNode && (
        <div
          style={{
            backgroundColor: 'var(--bg-surface)',
            border: '1px solid var(--border-default)',
            borderRadius: '0.5rem',
            padding: '1.25rem',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '0.75rem' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>Selected Node:</span>
              <span className="font-mono" style={{ fontWeight: 600, color: 'var(--text-main)' }}>{selectedNode.nodeId}</span>
            </div>
            {selectedNode.status && <StatusBadge status={selectedNode.status} />}
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '0.75rem', fontSize: '0.8125rem' }}>
            <div>
              <span style={{ color: 'var(--text-muted)' }}>Task Type:</span>{' '}
              <span className="font-mono" style={{ color: 'var(--accent-cyan)' }}>{selectedNode.taskType}</span>
            </div>
            <div>
              <span style={{ color: 'var(--text-muted)' }}>Max Attempts:</span>{' '}
              <span style={{ color: 'var(--text-main)' }}>{selectedNode.definition.maxAttempts}</span>
            </div>
            {selectedNode.definition.timeout && (
              <div>
                <span style={{ color: 'var(--text-muted)' }}>Timeout:</span>{' '}
                <span style={{ color: 'var(--text-main)' }}>{selectedNode.definition.timeout}</span>
              </div>
            )}
            {selectedNode.execution?.taskId && (
              <div>
                <span style={{ color: 'var(--text-muted)' }}>Chronos Task ID:</span>{' '}
                <CopyableId id={selectedNode.execution.taskId} />
              </div>
            )}
            {selectedNode.execution?.startedAt && (
              <div>
                <span style={{ color: 'var(--text-muted)' }}>Started:</span>{' '}
                <span style={{ color: 'var(--text-main)' }}>{formatDate(selectedNode.execution.startedAt)}</span>
              </div>
            )}
            {selectedNode.execution?.completedAt && (
              <div>
                <span style={{ color: 'var(--text-muted)' }}>Completed:</span>{' '}
                <span style={{ color: 'var(--text-main)' }}>{formatDate(selectedNode.execution.completedAt)}</span>
              </div>
            )}
            {selectedNode.execution?.failureReason && (
              <div style={{ gridColumn: '1 / -1', color: 'var(--accent-rose)' }}>
                <span>Failure Reason:</span> {selectedNode.execution.failureReason}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
