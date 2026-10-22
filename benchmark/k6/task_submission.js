import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 20 },
    { duration: '30s', target: 50 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<250'],
  },
};

const BASE_URL = __ENV.CHRONOS_API_URL || 'http://localhost:8080/api/v1';
const AUTH_TOKEN = __ENV.CHRONOS_AUTH_TOKEN || '';

export default function () {
  const payload = JSON.stringify({
    taskType: 'BENCHMARK_BURST',
    payload: JSON.stringify({ iteration: __ITER, vu: __VU, timestamp: Date.now() }),
    priority: 'NORMAL',
    tenantId: 'tenant-bench-01',
    maxAttempts: 3,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Request-Id': `k6-sub-${__VU}-${__ITER}-${Date.now()}`,
      ...(AUTH_TOKEN ? { 'Authorization': `Bearer ${AUTH_TOKEN}` } : {}),
    },
  };

  const res = http.post(`${BASE_URL}/tasks`, payload, params);

  check(res, {
    'task submitted successfully (201/200)': (r) => r.status === 201 || r.status === 200,
    'has valid taskId': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body && body.taskId !== undefined;
      } catch (e) {
        return false;
      }
    },
  });

  sleep(0.05);
}
