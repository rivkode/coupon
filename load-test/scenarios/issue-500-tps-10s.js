// 500 TPS × 10 초 짧은 부하 — 빠른 회귀 / 튜닝 사이클용.
// 60 초 시나리오 (issue-500-tps.js) 는 결과 안정성 / drain 측정에 좋고,
// 본 10 초 시나리오는 환경 튜닝 후 즉시 영향을 보고 싶을 때 사용.
//
// 실행:
//   ./load-test/run-500-10-integrated.sh
//   k6 run load-test/scenarios/issue-500-tps-10s.js   (인프라/시드 별도 준비)

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders } from '../lib/payload.js';

const accepted = new Counter('issue_accepted');
const duplicate = new Counter('issue_duplicate');
const soldOut = new Counter('issue_sold_out');
const internalError = new Counter('issue_internal_error');
const otherError = new Counter('issue_other');

const EVENT_ID = Number(__ENV.EVENT_ID || 1);
const COUPON_TYPE_ID = Number(__ENV.COUPON_TYPE_ID || 1);

export const options = {
    scenarios: {
        steady_500_10s: {
            executor: 'constant-arrival-rate',
            rate: 500,
            timeUnit: '1s',
            duration: '10s',
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
    } else if (res.status === 200 && status === 'SOLD_OUT') {
        // ADR-011 — A 진입 단락 (negative cache HIT).
        soldOut.add(1);
    } else if (res.status === 503) {
        internalError.add(1);
    } else {
        otherError.add(1);
    }

    check(res, {
        'status is 200 or 503': (r) => r.status === 200 || r.status === 503,
    });
}
