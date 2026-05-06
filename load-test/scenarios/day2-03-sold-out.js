// Day 2 Scenario 3 — SOLD_OUT (Stub hook 또는 통합 + 재고 0)
//
// Stub 모드: idempotency-key prefix 'sold-out-' 트리거로 StubCouponIssuingClient 가 SOLD_OUT 강제.
// 통합 모드: server-b 가 실제로 재고 0 인 샤드를 만나도 같은 응답 (Lua DECR 음수 → INCR 롤백).
//
// 기대: 200 + status FAILED + failureReason "stock exhausted" (server-b common.IssueResult.soldOut).
//
// 실행 (stub): k6 run load-test/scenarios/day2-03-sold-out.js

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
    const userId = '20301';
    // Stub hook 트리거 — UUID 형태 유지하면서 prefix 매칭.
    const triggerKey = `sold-out-${Date.now()}-${__ITER}`;
    const headers = buildHeaders(userId, triggerKey);
    const res = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), { headers });

    let body;
    try { body = res.json(); } catch (_) { body = null; }

    check(res, {
        '[d2-03] HTTP 200': (r) => r.status === 200,
        '[d2-03] body.success == true': () => body && body.success === true,
        '[d2-03] data.status == FAILED': () => body && body.data && body.data.status === 'FAILED',
        '[d2-03] data.couponCode 미발급(null/없음)': () =>
            body && body.data && (body.data.couponCode === undefined || body.data.couponCode === null),
        '[d2-03] failureReason 포함 stock': () =>
            body && body.data && typeof body.data.failureReason === 'string' &&
            body.data.failureReason.toLowerCase().includes('stock'),
    });
}
