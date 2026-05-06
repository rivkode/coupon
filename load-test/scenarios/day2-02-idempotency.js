// Day 2 Scenario 2 — Idempotency (같은 키 두 번째 호출 → 동일 응답)
//
// 기대:
//   첫 번째: 200 + status SUCCEEDED + couponCode A
//   두 번째: 200 + status SUCCEEDED + couponCode A (같은 코드, server-a IdempotencyFilter 캐시 hit)
//
// 통합 모드에서도 server-a IdempotencyFilter (Redis SETNX, ADR-004) 가 server-b 호출을 차단.
//
// 실행: k6 run load-test/scenarios/day2-02-idempotency.js

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders, newIdempotencyKey } from '../lib/payload.js';

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        checks: ['rate==1.0'],
    },
};

export default function () {
    const userId = '20201';
    const sharedKey = newIdempotencyKey();
    const body = buildBody();

    const first = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, body, {
        headers: buildHeaders(userId, sharedKey),
    });
    const second = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, body, {
        headers: buildHeaders(userId, sharedKey),
    });

    let firstBody, secondBody;
    try { firstBody = first.json(); } catch (_) { firstBody = null; }
    try { secondBody = second.json(); } catch (_) { secondBody = null; }

    check(first, {
        '[d2-02] 1st HTTP 200': (r) => r.status === 200,
        '[d2-02] 1st status SUCCEEDED': () => firstBody && firstBody.data && firstBody.data.status === 'SUCCEEDED',
        '[d2-02] 1st couponCode 12 chars': () =>
            firstBody && firstBody.data && typeof firstBody.data.couponCode === 'string' && firstBody.data.couponCode.length === 12,
    });
    check(second, {
        '[d2-02] 2nd HTTP 200': (r) => r.status === 200,
        '[d2-02] 2nd status SUCCEEDED': () => secondBody && secondBody.data && secondBody.data.status === 'SUCCEEDED',
        '[d2-02] 2nd couponCode == 1st (멱등)': () =>
            firstBody && secondBody && firstBody.data && secondBody.data &&
            firstBody.data.couponCode === secondBody.data.couponCode,
        '[d2-02] 2nd requestId == 1st': () =>
            firstBody && secondBody && firstBody.data && secondBody.data &&
            firstBody.data.requestId === secondBody.data.requestId,
    });
}
