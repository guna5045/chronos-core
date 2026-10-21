import { request } from './client';
import { CreateTaskRequest, PagedResponse, TaskCancelRequest, TaskResponse, TaskRetryRequest } from './types';

export interface TaskQueryParams {
  tenantId?: string;
  status?: string;
  taskType?: string;
  workerId?: string;
  page?: number;
  size?: number;
}

export async function getTasks(params: TaskQueryParams = {}): Promise<PagedResponse<TaskResponse>> {
  const query = new URLSearchParams();
  if (params.tenantId) query.set('tenantId', params.tenantId);
  if (params.status) query.set('status', params.status);
  if (params.taskType) query.set('taskType', params.taskType);
  if (params.workerId) query.set('workerId', params.workerId);
  if (params.page !== undefined) query.set('page', params.page.toString());
  if (params.size !== undefined) query.set('size', params.size.toString());

  const queryString = query.toString();
  return request<PagedResponse<TaskResponse>>(`/tasks${queryString ? `?${queryString}` : ''}`);
}

export async function getTask(taskId: string): Promise<TaskResponse> {
  return request<TaskResponse>(`/tasks/${taskId}`);
}

export async function createTask(
  body: CreateTaskRequest,
  idempotencyKeyHeader?: string
): Promise<TaskResponse> {
  const headers: Record<string, string> = {};
  if (idempotencyKeyHeader) {
    headers['Idempotency-Key'] = idempotencyKeyHeader;
  }

  return request<TaskResponse>('/tasks', {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  });
}

export async function retryTask(taskId: string, body: TaskRetryRequest = {}): Promise<TaskResponse> {
  return request<TaskResponse>(`/tasks/${taskId}/retry`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export async function cancelTask(taskId: string, body: TaskCancelRequest = {}): Promise<TaskResponse> {
  return request<TaskResponse>(`/tasks/${taskId}/cancel`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}
