import { request } from './client';
import { TenantPolicyResponse, UpdateTenantPolicyRequest } from './types';

export async function getTenantPolicy(tenantId: string): Promise<TenantPolicyResponse> {
  return request<TenantPolicyResponse>(`/tenants/${tenantId}/policy`);
}

export async function updateTenantPolicy(
  tenantId: string,
  body: UpdateTenantPolicyRequest
): Promise<TenantPolicyResponse> {
  return request<TenantPolicyResponse>(`/tenants/${tenantId}/policy`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}
