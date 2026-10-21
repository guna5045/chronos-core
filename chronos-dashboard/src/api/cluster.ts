import { request } from './client';
import { ClusterOverviewResponse } from './types';

export async function getClusterOverview(): Promise<ClusterOverviewResponse> {
  return request<ClusterOverviewResponse>('/cluster');
}
