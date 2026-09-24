// Load test for the URL shortener. Run with: k6 run loadtest/redirect_load.js
// Install k6: https://k6.io/docs/get-started/installation/
//
// This exercises the two paths that matter most for the "at scale" claim:
//  - a low-volume create workload (writes are rare)
//  - a high-volume redirect workload (reads are the actual scale target)
//
// Set BASE_URL to your deployed instance, e.g.:
//   k6 run -e BASE_URL=https://your-app.fly.dev loadtest/redirect_load.js

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    // Simulates campaign-launch bursts: ramps to a sustained high redirect rate.
    redirect_burst: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 500,
      stages: [
        { target: 500, duration: '30s' },   // ramp up
        { target: 5000, duration: '1m' },   // sustained burst - matches the ~5k/s design target
        { target: 500, duration: '30s' },   // ramp down
      ],
      exec: 'redirectFlow',
    },
    // Low-volume, constant create workload alongside the redirect burst.
    create_baseline: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 20,
      exec: 'createFlow',
    },
  },
  thresholds: {
    // The claim we're actually testing: redirect p99 stays under 15ms even under load.
    'http_req_duration{scenario:redirect_burst}': ['p(99)<15'],
    'http_req_failed{scenario:redirect_burst}': ['rate<0.01'],
  },
};

// Pre-created during setup() so the redirect burst hits a real, existing code.
export function setup() {
  const res = http.post(`${BASE_URL}/api/v1/shorten`,
    JSON.stringify({ url: 'https://example.com/loadtest-target' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  const code = JSON.parse(res.body).code;
  return { code };
}

export function redirectFlow(data) {
  const res = http.get(`${BASE_URL}/${data.code}`, { redirects: 0 });
  check(res, { 'redirect status is 302': (r) => r.status === 302 });
}

export function createFlow() {
  const res = http.post(`${BASE_URL}/api/v1/shorten`,
    JSON.stringify({ url: `https://example.com/page/${Math.random()}` }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(res, { 'create status is 200': (r) => r.status === 200 });
}
