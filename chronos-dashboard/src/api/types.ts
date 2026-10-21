// Task Types & Enums
export type TaskStatus =
  | 'CREATED'
  | 'QUEUED'
  | 'DISPATCHED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'DLQ';

export type TaskPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL';

export interface TaskResponse {
  taskId: string;
  taskType: string;
  tenantId: string;
  priority: TaskPriority;
  status: TaskStatus;
  assignedWorkerId: string | null;
  currentAttempt: number;
  maxAttempts: number;
  createdAt: string;
  scheduledAt: string | null;
  deadline: string | null;
  timeout: string | null;
  version: number;
  payload: string | null;
  idempotencyKey: string | null;
  concurrencyKey: string | null;
}

export interface CreateTaskRequest {
  taskType: string;
  payload: string;
  priority?: TaskPriority;
  tenantId: string;
  idempotencyKey?: string;
  scheduledAt?: string;
  deadline?: string;
  timeout?: string;
  maxAttempts?: number;
  concurrencyKey?: string;
}

export interface TaskRetryRequest {
  reason?: string;
}

export interface TaskCancelRequest {
  reason?: string;
}

// Workflow Types & Enums
export type WorkflowVersionStatus = 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';

export type WorkflowExecutionStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED';

export type WorkflowNodeExecutionStatus =
  | 'PENDING'
  | 'READY'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'SKIPPED'
  | 'CANCELLED';

export interface WorkflowNodeResponse {
  nodeId: string;
  taskType: string;
  payload: string;
  priority: string;
  timeout: string | null;
  maxAttempts: number;
}

export interface WorkflowEdge {
  sourceNodeId: string;
  targetNodeId: string;
}

export interface WorkflowVersionResponse {
  versionId: string;
  workflowId: string;
  versionNumber: number;
  status: WorkflowVersionStatus;
  createdAt: string;
  publishedAt: string | null;
  nodes: WorkflowNodeResponse[];
  edges: WorkflowEdge[];
}

export interface WorkflowDefinitionResponse {
  workflowId: string;
  name: string;
  description: string;
  createdAt: string;
  activeVersion: WorkflowVersionResponse | null;
}

export interface WorkflowExecutionResponse {
  executionId: string;
  workflowId: string;
  versionId: string;
  versionNumber: number;
  status: WorkflowExecutionStatus;
  tenantId: string;
  startedAt: string;
  completedAt: string | null;
  failureReason: string | null;
}

export interface WorkflowExecutionNodeState {
  nodeId: string;
  status: WorkflowNodeExecutionStatus;
  taskId: string | null;
  startedAt: string | null;
  completedAt: string | null;
  failureReason: string | null;
}

export interface WorkflowExecutionDetailsResponse {
  execution: WorkflowExecutionResponse;
  nodes: WorkflowExecutionNodeState[];
}

export interface CreateWorkflowRequest {
  name: string;
  description?: string;
}

export interface CreateWorkflowVersionRequest {
  nodes: {
    nodeId: string;
    taskType: string;
    payload?: string;
    priority?: string;
    timeout?: string;
    maxAttempts?: number;
  }[];
  edges: WorkflowEdge[];
}

export interface ExecuteWorkflowRequest {
  versionId?: string;
  tenantId: string;
}

// Worker & Cluster Types
export type WorkerState = 'REGISTERING' | 'ACTIVE' | 'SUSPECTED' | 'REMOVED';

export interface WorkerResponse {
  workerId: string;
  hostname: string;
  port: number;
  state: WorkerState;
  maxConcurrency: number;
  activeTasks: number;
  availableCapacity: number;
  supportedTaskTypes: string[];
  registeredAt: string;
  lastHeartbeatAt: string;
  sessionId: string;
}

export interface ClusterOverviewResponse {
  coordinatorId: string;
  isLeader: boolean;
  leaderEpoch: number;
  totalWorkers: number;
  activeWorkers: number;
  suspectedWorkers: number;
  totalClusterCapacity: number;
  availableClusterCapacity: number;
  activeClusterTasks: number;
}

// DLQ Types
export interface ExecutionAttemptRecord {
  attemptId: string;
  taskId: string;
  workerId: string;
  attemptNumber: number;
  status: string;
  startedAt: string;
  completedAt: string | null;
  errorCode: string | null;
  errorMessage: string | null;
}

export interface TaskAuditRecord {
  auditId: string;
  taskId: string;
  fromStatus: string;
  toStatus: string;
  transitionReason: string;
  metadata: string | null;
  recordedAt: string;
}

export interface DlqInspectionResponse {
  task: TaskResponse;
  attempts: ExecutionAttemptRecord[];
  auditRecords: TaskAuditRecord[];
}

export interface DlqReplayRequest {
  reason?: string;
}

export interface DlqArchiveRequest {
  reason?: string;
}

// Tenant Policy Types
export interface TenantPolicyResponse {
  tenantId: string;
  maxConcurrentTasks: number;
  maxQueuedTasks: number;
  tasksPerSecond: number;
  burstCapacity: number;
  maxWorkflowExecutions: number;
}

export interface UpdateTenantPolicyRequest {
  maxConcurrentTasks: number;
  maxQueuedTasks: number;
  tasksPerSecond: number;
  burstCapacity: number;
  maxWorkflowExecutions: number;
}

// Pagination & Error Types
export interface PagedResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface ApiErrorResponse {
  timestamp: string;
  status: number;
  code: string;
  message: string;
  path: string;
  fieldErrors?: Record<string, string>;
}

export interface AuthTokenResponse {
  token: string;
  tokenType: string;
  expiresInSeconds: number;
  roles: string[];
  tenantId: string;
}
