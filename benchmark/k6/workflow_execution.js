import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 5 },
    { duration: '20s', target: 15 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<500'],
  },
};

const BASE_URL = __ENV.CHRONOS_API_URL || 'http://localhost:8080/api/v1';
const AUTH_TOKEN = __ENV.CHRONOS_AUTH_TOKEN || '';

export default function () {
  const headers = {
    'Content-Type': 'application/json',
    'X-Request-Id': `k6-wf-${__VU}-${Date.now()}`,
    ...(AUTH_TOKEN ? { 'Authorization': `Bearer ${AUTH_TOKEN}` } : {}),
  };

  // Query workflow templates
  const workflowsRes = http.get(`${BASE_URL}/workflows`, { headers });
  check(workflowsRes, {
    'workflow list retrieved': (r) => r.status === 200,
  });

  sleep(0.2);
}
