import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 15 },
    { duration: '30s', target: 30 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<300'],
  },
};

const BASE_URL = __ENV.CHRONOS_API_URL || 'http://localhost:8080/api/v1';
const AUTH_TOKEN = __ENV.CHRONOS_AUTH_TOKEN || '';

export default function () {
  const headers = {
    'Content-Type': 'application/json',
    'X-Request-Id': `k6-mixed-${__VU}-${__ITER}-${Date.now()}`,
    ...(AUTH_TOKEN ? { 'Authorization': `Bearer ${AUTH_TOKEN}` } : {}),
  };

  // 1. Submit a task
  const submitPayload = JSON.stringify({
    taskType: 'ORDER_PROCESSING',
    payload: JSON.stringify({ orderId: `ord-${__VU}-${__ITER}`, amount: 99.50 }),
    priority: 'HIGH',
    tenantId: 'tenant-ecommerce',
  });

  const submitRes = http.post(`${BASE_URL}/tasks`, submitPayload, { headers });
  check(submitRes, {
    'submission succeeded': (r) => r.status === 201 || r.status === 200,
  });

  if (submitRes.status === 201 || submitRes.status === 200) {
    const task = JSON.parse(submitRes.body);
    const taskId = task.taskId;

    // 2. Query task detail
    const getRes = http.get(`${BASE_URL}/tasks/${taskId}`, { headers });
    check(getRes, {
      'get task succeeded': (r) => r.status === 200,
    });
  }

  // 3. Cluster overview telemetry query
  const clusterRes = http.get(`${BASE_URL}/cluster/overview`, { headers });
  check(clusterRes, {
    'cluster telemetry query succeeded': (r) => r.status === 200,
  });

  sleep(0.1);
}
