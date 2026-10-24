import React from 'react';
import { VisualizerId } from '../../../docs/types';
import { TaskLifecycleVisualizer } from './TaskLifecycleVisualizer';
import { LeaderElectionVisualizer } from './LeaderElectionVisualizer';
import { WorkerHeartbeatVisualizer } from './WorkerHeartbeatVisualizer';
import { ConsistentHashVisualizer } from './ConsistentHashVisualizer';
import { DagExecutionVisualizer } from './DagExecutionVisualizer';
import { DlqLifecycleVisualizer } from './DlqLifecycleVisualizer';
import { DispatchVisualizer } from './DispatchVisualizer';
import { AiWorkflowVisualizer } from './AiWorkflowVisualizer';
import { AiOperationsVisualizer } from './AiOperationsVisualizer';
import { ObservabilityTraceVisualizer } from './ObservabilityTraceVisualizer';

interface Props {
  visualizerId?: VisualizerId;
}

export const VisualizerRenderer: React.FC<Props> = ({ visualizerId }) => {
  if (!visualizerId) return null;

  switch (visualizerId) {
    case 'task-lifecycle':
      return <TaskLifecycleVisualizer />;
    case 'leader-election':
      return <LeaderElectionVisualizer />;
    case 'worker-heartbeat':
      return <WorkerHeartbeatVisualizer />;
    case 'consistent-hashing':
      return <ConsistentHashVisualizer />;
    case 'dag-execution':
      return <DagExecutionVisualizer />;
    case 'dlq-lifecycle':
      return <DlqLifecycleVisualizer />;
    case 'dispatch':
      return <DispatchVisualizer />;
    case 'ai-workflow':
      return <AiWorkflowVisualizer />;
    case 'ai-operations':
      return <AiOperationsVisualizer />;
    case 'observability-trace':
      return <ObservabilityTraceVisualizer />;
    default:
      return null;
  }
};
