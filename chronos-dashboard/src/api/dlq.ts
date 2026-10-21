import { request } from './client';
import { DlqArchiveRequest, DlqInspectionResponse, DlqReplayRequest, PagedResponse, TaskResponse } from './types';

export async function getDlqTasks(page: number = 0, size: number = 20): Promise<PagedResponse<TaskResponse>> {
  return request<PagedResponse<TaskResponse>>(`/dlq?page=${page}&size=${size}`);
}

export async function inspectDlqTask(taskId: string): Promise<DlqInspectionResponse> {
  return request<DlqInspectionResponse>(`/dlq/${taskId}`);
}

export async function replayDlqTask(taskId: string, body: DlqReplayRequest = {}): Promise<TaskResponse> {
  return request<TaskResponse>(`/dlq/${taskId}/replay`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export async function archiveDlqTask(taskId: string, body: DlqArchiveRequest = {}): Promise<TaskResponse> {
  return request<TaskResponse>(`/dlq/${taskId}/archive`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}
