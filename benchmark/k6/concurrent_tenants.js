import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 20 },
    { duration: '20s', target: 40 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<350'],
  },
};

const BASE_URL = __ENV.CHRONOS_API_URL || 'http://localhost:8080/api/v1';
const AUTH_TOKEN = __ENV.CHRONOS_AUTH_TOKEN || '';
const TENANTS = ['tenant-alpha', 'tenant-beta', 'tenant-gamma', 'tenant-delta'];

export default function () {
  const tenantId = TENANTS[__VU % TENANTS.length];
  const headers = {
    'Content-Type': 'application/json',
    'X-Request-Id': `k6-tenant-${tenantId}-${Date.now()}`,
    ...(AUTH_TOKEN ? { 'Authorization': `Bearer ${AUTH_TOKEN}` } : {}),
  };

  const payload = JSON.stringify({
    taskType: 'TENANT_ISOLATED_OP',
    payload: JSON.stringify({ tenant: tenantId, timestamp: Date.now() }),
    priority: 'NORMAL',
    tenantId: tenantId,
  });

  const res = http.post(`${BASE_URL}/tasks`, payload, { headers });
  check(res, {
    'accepted or bounded by rate limit (201/200/429)': (r) =>
      r.status === 201 || r.status === 200 || r.status === 429,
  });

  sleep(0.05);
}
