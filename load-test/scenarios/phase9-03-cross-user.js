// Phase 9 Scenario 3 — 다른 사용자가 같은 Idempotency-Key 사용
//
// 검증 대상: server-a 의 `(user_id, idempotency_key) UNIQUE` (CLAUDE.md §9) + server-b 의
// user-scoped idem 1차 캐시 (`coupon:idem:{userId}:{key}`, ADR-004 정합). 두 user 가 같은
// idem 을 써도 각자 별도의 발급 결과를 받는다.
//
// 기대 (통합 환경):
//   - 두 user 모두 HTTP 200
//   - requestId 가 서로 다름 (server-a 가 user 별 별도 IssueRequest 생성)
//   - couponCode 가 서로 다름 (server-b 가 user-scoped 캐시로 별도 코드 발급)

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
        '[03] user 9301 status SUCCEEDED': () => bA && bA.data && bA.data.status === 'SUCCEEDED',
        '[03] user 9302 status SUCCEEDED': () => bB && bB.data && bB.data.status === 'SUCCEEDED',
        '[03] requestId 가 서로 다름': () =>
            bA && bB && bA.data && bB.data && bA.data.requestId !== bB.data.requestId,
        '[03] couponCode 가 서로 다름': () =>
            bA && bB && bA.data && bB.data && bA.data.couponCode !== bB.data.couponCode,
    });
}
