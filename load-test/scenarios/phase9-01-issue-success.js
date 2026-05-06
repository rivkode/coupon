// Phase 9 Scenario 1 — 정상 발급
//
// 기대: 200 + body.success == true + couponCode 12 chars + status SUCCEEDED.
//
// 실행: k6 run load-test/scenarios/phase9-01-issue-success.js
//      (옵션) k6 run -e BASE_URL=http://localhost:8080 ...

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders } from '../lib/payload.js';

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        // 본 스크립트의 모든 assertion 이 통과해야 한다.
        checks: ['rate==1.0'],
    },
};

export default function () {
    const userId = `9101${__ITER}`;
    const headers = buildHeaders(userId);
    const res = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), { headers });

    let body;
    try { body = res.json(); } catch (_) { body = null; }

    check(res, {
        '[01] HTTP 200': (r) => r.status === 200,
        '[01] body.success == true': () => body && body.success === true,
        '[01] data.status == SUCCEEDED': () => body && body.data && body.data.status === 'SUCCEEDED',
        '[01] data.couponCode 12 chars': () => body && body.data && typeof body.data.couponCode === 'string' && body.data.couponCode.length === 12,
        '[01] data.requestId 가 UUID': () => body && body.data && /^[0-9a-f-]{36}$/i.test(body.data.requestId || ''),
    });
}
