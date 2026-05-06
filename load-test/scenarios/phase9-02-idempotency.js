// Phase 9 Scenario 2 — 멱등성 (같은 Idempotency-Key 두 번)
//
// 기대: 두 응답의 requestId / couponCode 가 동일. DB row 1개 (수동 확인).

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
    const userId = '9201';
    const key = newIdempotencyKey();
    const headers = buildHeaders(userId, key);
    const body = buildBody();

    const res1 = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, body, { headers });
    const res2 = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, body, { headers });

    let b1, b2;
    try { b1 = res1.json(); } catch (_) { b1 = null; }
    try { b2 = res2.json(); } catch (_) { b2 = null; }

    check(res1, { '[02] 1st HTTP 200': (r) => r.status === 200 });
    check(res2, { '[02] 2nd HTTP 200': (r) => r.status === 200 });
    check(null, {
        '[02] 같은 requestId': () => b1 && b2 && b1.data && b2.data && b1.data.requestId === b2.data.requestId,
        '[02] 같은 couponCode (캐시 hit)': () => b1 && b2 && b1.data && b2.data && b1.data.couponCode === b2.data.couponCode,
        '[02] 두 응답 모두 SUCCEEDED': () => b1 && b2 && b1.data.status === 'SUCCEEDED' && b2.data.status === 'SUCCEEDED',
    });
}
