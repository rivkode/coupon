// Phase 9 Scenario 3 — 다른 사용자가 같은 Idempotency-Key 사용
//
// 기대: 두 사용자 모두 정상 발급 (key 가 user-scoped 이므로 충돌 없음).
//      requestId / couponCode 는 서로 다름.

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
    const sharedKey = newIdempotencyKey();
    const body = buildBody();

    const resA = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, body, { headers: buildHeaders('9301', sharedKey) });
    const resB = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, body, { headers: buildHeaders('9302', sharedKey) });

    let bA, bB;
    try { bA = resA.json(); } catch (_) { bA = null; }
    try { bB = resB.json(); } catch (_) { bB = null; }

    check(resA, { '[03] user 9301 HTTP 200': (r) => r.status === 200 });
    check(resB, { '[03] user 9302 HTTP 200': (r) => r.status === 200 });
    check(null, {
        '[03] requestId 가 서로 다름': () => bA && bB && bA.data && bB.data && bA.data.requestId !== bB.data.requestId,
        '[03] couponCode 가 서로 다름': () => bA && bB && bA.data && bB.data && bA.data.couponCode !== bB.data.couponCode,
    });
}
