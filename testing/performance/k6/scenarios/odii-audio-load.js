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
    http_req_duration: ['p(95)<150'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

export default function () {
  const storyId = 'story-jeonju-01';
  const res = http.get(`${BASE_URL}/api/v1/odii/stories/${storyId}`, {
    headers: { 'Accept': 'application/json' },
    tags: { name: 'GetOdiiStory' },
  });

  check(res, {
    'odii story status valid': (r) => r.status === 200 || r.status === 404,
  });

  sleep(0.1);
}
