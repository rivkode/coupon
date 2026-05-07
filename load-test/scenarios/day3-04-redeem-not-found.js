// Day 3 Scenario 4 — 존재하지 않는 코드의 redeem 시도는 404 (마스킹과 동일 응답)
//
// 검증: server-c RedeemCouponService 의 마스킹 일관성 — "다른 user 의 코드" (day3-03) 와
// "코드 없음" 둘 다 동일 404 + error.code=NOT_FOUND.
//
// 실행: k6 run -e SERVER_C_BASE_URL=http://localhost:8082 \
//        load-test/scenarios/day3-04-redeem-not-found.js

import http from 'k6/http';
import { check } from 'k6';
import { SERVER_C_BASE_URL, redeemPath } from '../lib/env.js';
import { buildRedeemHeaders, newIdempotencyKey } from '../lib/payload.js';

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        checks: ['rate==1.0'],
    },
};

export default function () {
    // CouponCode VO 의 검증을 통과해야 service 까지 도달 — 12자 + 허용 문자 (Crockford base32).
    // 길이 / 알파벳은 valid 하지만 발급된 적 없는 코드.
    const fakeCode = 'ZZZZZZZZZZZZ';
    const userId = '93040';

    const res = http.post(`${SERVER_C_BASE_URL}${redeemPath(fakeCode)}`, null, {
        headers: buildRedeemHeaders(userId, newIdempotencyKey()),
    });
    let body;
    try { body = res.json(); } catch (_) { body = null; }

    check(res, {
        '[d3-04] HTTP 404': (r) => r.status === 404,
        '[d3-04] error.code=NOT_FOUND': () => body?.error?.code === 'NOT_FOUND',
        '[d3-04] success=false': () => body?.success === false,
        '[d3-04] error.message 마스킹 — code 누설 안 함': () =>
            typeof body?.error?.message === 'string' && !body.error.message.includes(fakeCode),
    });
}
