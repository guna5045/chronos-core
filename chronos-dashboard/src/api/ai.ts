import { request } from './client';
import {
  AiActionStatus,
  AiOperationalAction,
  AiStatusResponse,
  AiWorkflowProposal,
  GenerateWorkflowRequest,
  IncidentAnalysisRequest,
  IncidentAnalysisResponse,
} from './types';

export const aiApi = {
  getStatus: (): Promise<AiStatusResponse> => {
    return request<AiStatusResponse>('/ai/status');
  },

  generateWorkflowProposal: (data: GenerateWorkflowRequest): Promise<AiWorkflowProposal> => {
    return request<AiWorkflowProposal>('/ai/workflows/generate', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  listProposals: (tenantId?: string): Promise<AiWorkflowProposal[]> => {
    const params = new URLSearchParams();
    if (tenantId) params.append('tenantId', tenantId);
    const query = params.toString();
    return request<AiWorkflowProposal[]>(`/ai/workflows${query ? `?${query}` : ''}`);
  },

  getProposal: (proposalId: string): Promise<AiWorkflowProposal> => {
    return request<AiWorkflowProposal>(`/ai/workflows/${proposalId}`);
  },

  approveWorkflowProposal: (
    proposalId: string
  ): Promise<{ workflowId: string; name: string; version: number; status: string }> => {
    return request<{ workflowId: string; name: string; version: number; status: string }>(
      `/ai/workflows/${proposalId}/approve`,
      {
        method: 'POST',
      }
    );
  },

  rejectWorkflowProposal: (
    proposalId: string,
    reason?: string
  ): Promise<{ status: string; proposalId: string }> => {
    return request<{ status: string; proposalId: string }>(`/ai/workflows/${proposalId}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
  },

  analyzeIncident: (data: IncidentAnalysisRequest): Promise<IncidentAnalysisResponse> => {
    return request<IncidentAnalysisResponse>('/ai/incidents/analyze', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  listActions: (tenantId?: string, status?: AiActionStatus): Promise<AiOperationalAction[]> => {
    const params = new URLSearchParams();
    if (tenantId) params.append('tenantId', tenantId);
    if (status) params.append('status', status);
    const query = params.toString();
    return request<AiOperationalAction[]>(`/ai/actions${query ? `?${query}` : ''}`);
  },

  getAction: (actionId: string): Promise<AiOperationalAction> => {
    return request<AiOperationalAction>(`/ai/actions/${actionId}`);
  },

  approveAction: (actionId: string, reason?: string): Promise<AiOperationalAction> => {
    return request<AiOperationalAction>(`/ai/actions/${actionId}/approve`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
  },

  rejectAction: (
    actionId: string,
    reason?: string
  ): Promise<{ status: string; actionId: string }> => {
    return request<{ status: string; actionId: string }>(`/ai/actions/${actionId}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
  },
};
