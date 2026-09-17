import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 20 },
    { duration: '20s', target: 50 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'], // 에러율 1% 미만
    http_req_duration: ['p(95)<150'], // p95 레이턴시 150ms 미만
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

export default function () {
  const regions = ['kr-45-jeonju', 'kr-11-jongno', 'kr-26-haeundae', 'kr-47-gyeongju'];
  const region = regions[Math.floor(Math.random() * regions.length)];
  
  const res = http.get(`${BASE_URL}/api/v1/hanoks?regionCode=${region}&limit=20`, {
    headers: {
      'Accept': 'application/json',
    },
    tags: { name: 'HanokSearch' },
  });

  check(res, {
    'status is 200': (r) => r.status === 200,
    'schemaVersion is 1.2': (r) => r.json('schemaVersion') === '1.2',
    'has items': (r) => Array.isArray(r.json('items')),
  });

  sleep(0.1);
}
