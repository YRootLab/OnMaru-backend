import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 20 },
    { duration: '20s', target: 50 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200', 'max<2000'], // 2초 timeout 방어
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

export default function () {
  const regions = ['kr-45-jeonju', 'kr-11-jongno', 'kr-47-gyeongju', 'kr-42-gangneung'];
  const region = regions[Math.floor(Math.random() * regions.length)];

  // 1. 지역별 후기 집계 (Insights / Aggregation)
  const aggRes = http.get(`${BASE_URL}/api/v1/regions/${region}/insights`, {
    headers: { 'Accept': 'application/json' },
    tags: { name: 'RegionReviewAggregation' },
  });

  check(aggRes, {
    'aggregation status is 200 or 404': (r) => r.status === 200 || r.status === 404,
  });

  // 2. 후기 목록 조회 (Cursor pagination)
  const listRes = http.get(`${BASE_URL}/api/v1/visit-reviews?regionCode=${region}&limit=20`, {
    headers: { 'Accept': 'application/json' },
    tags: { name: 'ReviewList' },
  });

  check(listRes, {
    'list status is 200': (r) => r.status === 200,
  });

  sleep(0.1);
}
