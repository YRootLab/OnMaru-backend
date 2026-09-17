import http from 'k6/http';
import { check, group, sleep } from 'k6';

export const options = {
  scenarios: {
    hanok_search: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 20 },
        { duration: '20s', target: 40 },
        { duration: '10s', target: 0 },
      ],
      exec: 'hanokScenario',
    },
    review_aggregation: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 15 },
        { duration: '20s', target: 30 },
        { duration: '10s', target: 0 },
      ],
      exec: 'reviewScenario',
    },
  },
  thresholds: {
    'http_req_duration{scenario:hanok_search}': ['p(95)<150'],
    'http_req_duration{scenario:review_aggregation}': ['p(95)<200', 'max<2000'],
    'http_req_failed': ['rate<0.01'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

export function hanokScenario() {
  const regions = ['kr-45-jeonju', 'kr-11-jongno', 'kr-26-haeundae', 'kr-47-gyeongju'];
  const region = regions[Math.floor(Math.random() * regions.length)];
  const res = http.get(`${BASE_URL}/api/v1/hanoks?regionCode=${region}&limit=20`);
  check(res, { 'status is 200': (r) => r.status === 200 });
  sleep(0.1);
}

export function reviewScenario() {
  const regions = ['kr-45-jeonju', 'kr-11-jongno', 'kr-47-gyeongju'];
  const region = regions[Math.floor(Math.random() * regions.length)];
  const res = http.get(`${BASE_URL}/api/v1/visit-reviews?regionCode=${region}&limit=20`);
  check(res, { 'status is 200': (r) => r.status === 200 });
  sleep(0.15);
}
