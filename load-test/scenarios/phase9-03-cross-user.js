// Phase 9 Scenario 3 — 다른 사용자가 같은 Idempotency-Key 사용
//
// 검증 대상: server-a 의 `(user_id, idempotency_key) UNIQUE` (CLAUDE.md §9) — 다른 user 가
// 같은 idem 을 써도 server-a 의 멱등성 정책이 충돌하지 않고 각자 별도 발급 record 를 만든다.
//
// 기대 (통합 환경):
//   - 두 user 모두 HTTP 200
//   - requestId 가 서로 다름 (server-a 가 user 별 별도 IssueRequest 생성)
//
// 알려진 trade-off — couponCode 는 같을 수 있음:
//   server-b 의 idem 1차 캐시는 idem-only 키 (`coupon:idem:{key}`). 같은 idem 으로 두 번째 호출
//   (다른 user 라도) 은 server-b 캐시 hit 으로 ALREADY_ISSUED + 첫 코드 반환. 즉 본 시나리오는
//   server-a 의 user-scoped UNIQUE 만 직접 검증하고, server-b 의 idem 캐시 정책 (user-scoped 로
//   확장 여부) 은 ADR-004 후속 검토 영역 (README trade-off 참조).

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
    });
}
