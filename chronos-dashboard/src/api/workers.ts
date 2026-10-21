import { request } from './client';
import { WorkerResponse } from './types';

export async function getWorkers(): Promise<WorkerResponse[]> {
  return request<WorkerResponse[]>('/workers');
}

export async function getWorker(workerId: string): Promise<WorkerResponse> {
  return request<WorkerResponse>(`/workers/${workerId}`);
}
