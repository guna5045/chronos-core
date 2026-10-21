import { request } from './client';
import {
  CreateWorkflowRequest,
  CreateWorkflowVersionRequest,
  ExecuteWorkflowRequest,
  WorkflowDefinitionResponse,
  WorkflowExecutionDetailsResponse,
  WorkflowExecutionResponse,
  WorkflowVersionResponse,
} from './types';

export async function getWorkflows(): Promise<WorkflowDefinitionResponse[]> {
  return request<WorkflowDefinitionResponse[]>('/workflows');
}

export async function getWorkflow(workflowId: string): Promise<WorkflowDefinitionResponse> {
  return request<WorkflowDefinitionResponse>(`/workflows/${workflowId}`);
}

export async function getWorkflowVersions(workflowId: string): Promise<WorkflowVersionResponse[]> {
  return request<WorkflowVersionResponse[]>(`/workflows/${workflowId}/versions`);
}

export async function createWorkflow(body: CreateWorkflowRequest): Promise<WorkflowDefinitionResponse> {
  return request<WorkflowDefinitionResponse>('/workflows', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export async function createWorkflowVersion(
  workflowId: string,
  body: CreateWorkflowVersionRequest
): Promise<WorkflowVersionResponse> {
  return request<WorkflowVersionResponse>(`/workflows/${workflowId}/versions`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export async function publishWorkflowVersion(
  workflowId: string,
  versionNumber: number
): Promise<WorkflowVersionResponse> {
  return request<WorkflowVersionResponse>(`/workflows/${workflowId}/versions/${versionNumber}/publish`, {
    method: 'POST',
  });
}

export async function deprecateWorkflowVersion(
  workflowId: string,
  versionNumber: number
): Promise<WorkflowVersionResponse> {
  return request<WorkflowVersionResponse>(`/workflows/${workflowId}/versions/${versionNumber}/deprecate`, {
    method: 'POST',
  });
}

export async function executeWorkflow(
  workflowId: string,
  body: ExecuteWorkflowRequest
): Promise<WorkflowExecutionResponse> {
  return request<WorkflowExecutionResponse>(`/workflows/${workflowId}/execute`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export async function getWorkflowExecution(
  executionId: string
): Promise<WorkflowExecutionDetailsResponse> {
  return request<WorkflowExecutionDetailsResponse>(`/workflow-executions/${executionId}`);
}

export async function cancelWorkflowExecution(
  executionId: string,
  reason: string = 'User requested cancellation'
): Promise<WorkflowExecutionResponse> {
  return request<WorkflowExecutionResponse>(`/workflow-executions/${executionId}/cancel`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}
