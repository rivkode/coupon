// 인스턴스당 500 TPS 발급 부하 — 1000 TPS 가 단일 server-a (1 vCPU) 한계를 초과한 결과
// (docs/reports/load-test-bottleneck-analysis.md) 에 따라 보다 보수적인 부하 수준에서
// 안정적 처리 가능성 / 재현성 검증.
//
// 실행:
//   k6 run load-test/scenarios/issue-500-tps.js
//   BASE_URL=http://localhost:8080 EVENT_ID=1 COUPON_TYPE_ID=1 k6 run ...

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders } from '../lib/payload.js';

const accepted = new Counter('issue_accepted');
const duplicate = new Counter('issue_duplicate');
const internalError = new Counter('issue_internal_error');
const otherError = new Counter('issue_other');

const EVENT_ID = Number(__ENV.EVENT_ID || 1);
const COUPON_TYPE_ID = Number(__ENV.COUPON_TYPE_ID || 1);

export const options = {
    scenarios: {
        steady_500: {
            executor: 'constant-arrival-rate',
            rate: 500,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 100,
            maxVUs: 300,
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<200', 'p(99)<400'],
        http_req_failed: ['rate<0.005'],
    },
};

export default function () {
    // 매 호출마다 다른 user_id (1 인 1 장 제약 회피, UNIQUE 미충돌).
    const userId = (__VU * 100000) + __ITER + 1;
    const headers = buildHeaders(userId);
    const body = buildBody(EVENT_ID, COUPON_TYPE_ID);

    const res = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, body, { headers });

    let parsed;
    try { parsed = res.json(); } catch (_) { parsed = null; }
    const status = parsed && parsed.data ? parsed.data.status : null;

    if (res.status === 200 && status === 'ACCEPTED') {
        accepted.add(1);
    } else if (res.status === 200 && status === 'DUPLICATE') {
        duplicate.add(1);
    } else if (res.status === 503) {
        internalError.add(1);
    } else {
        otherError.add(1);
    }

    check(res, {
        'status is 200 or 503': (r) => r.status === 200 || r.status === 503,
    });
}
