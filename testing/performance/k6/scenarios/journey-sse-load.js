import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 10 },
    { duration: '20s', target: 30 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<300'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

export default function () {
  // 1. Exploration 조회
  const expRes = http.get(`${BASE_URL}/api/v1/explorations/1c178047-07ec-4b4a-9a79-3901c53b0e5c`, {
    headers: { 'Accept': 'application/json' },
    tags: { name: 'GetExploration' },
  });

  check(expRes, {
    'get exploration status valid': (r) => r.status === 200 || r.status === 404,
  });

  // 2. Saved Journeys 목록 조회
  const savedRes = http.get(`${BASE_URL}/api/v1/saved-journeys?limit=20`, {
    headers: { 'Accept': 'application/json' },
    tags: { name: 'ListSavedJourneys' },
  });

  check(savedRes, {
    'saved journey status valid': (r) => r.status === 200 || r.status === 401,
  });

  sleep(0.2);
}
