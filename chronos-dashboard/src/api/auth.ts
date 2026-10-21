import { request } from './client';
import { AuthTokenResponse } from './types';

export interface DevTokenRequest {
  subject: string;
  roles: string[];
  tenantId: string;
}

export async function requestDevToken(body: DevTokenRequest): Promise<AuthTokenResponse> {
  return request<AuthTokenResponse>('/auth/token', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}
