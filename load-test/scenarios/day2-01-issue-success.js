// Day 2 Scenario 1 — 정상 발급 (Stub or 통합 모드)
//
// 기대: 200 + body.success == true + status SUCCEEDED + couponCode 12 chars.
//
// Stub 모드: server-a (--spring.profiles.active=local) 만 부팅.
// 통합 모드: server-a + server-b (8081 포트, redis 재고 seed 필요).
//
// 실행: k6 run load-test/scenarios/day2-01-issue-success.js

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders } from '../lib/payload.js';

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        checks: ['rate==1.0'],
    },
};

export default function () {
    const userId = `2010${__ITER}`;
    const headers = buildHeaders(userId);
    const res = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), { headers });

    let body;
    try { body = res.json(); } catch (_) { body = null; }

    check(res, {
        '[d2-01] HTTP 200': (r) => r.status === 200,
        '[d2-01] body.success == true': () => body && body.success === true,
        '[d2-01] data.status == SUCCEEDED': () => body && body.data && body.data.status === 'SUCCEEDED',
        '[d2-01] couponCode 12 chars': () =>
            body && body.data && typeof body.data.couponCode === 'string' && body.data.couponCode.length === 12,
        '[d2-01] requestId 가 UUID': () =>
            body && body.data && /^[0-9a-f-]{36}$/i.test(body.data.requestId || ''),
    });
}
