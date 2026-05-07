// Day 3 Scenario 5 — server-c 의 헤더 누락 검증 (GlobalExceptionHandler 회귀)
//
// 검증: X-User-Id 누락 시 400 + error.code=MISSING_HEADER. server-a 의 phase9-05 와 동일 패턴.
//
// 실행: k6 run -e SERVER_C_BASE_URL=http://localhost:8082 \
//        load-test/scenarios/day3-05-redeem-missing-header.js

import http from 'k6/http';
import { check } from 'k6';
import { SERVER_C_BASE_URL, redeemPath } from '../lib/env.js';

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        checks: ['rate==1.0'],
    },
};

export default function () {
    // X-User-Id 누락 — Idempotency-Key 만 보냄.
    const res = http.post(`${SERVER_C_BASE_URL}${redeemPath('ABCDEFGHJKMN')}`, null, {
        headers: { 'Idempotency-Key': '11111111-1111-1111-1111-111111111111' },
    });
    let body;
    try { body = res.json(); } catch (_) { body = null; }

    check(res, {
        '[d3-05] HTTP 400': (r) => r.status === 400,
        '[d3-05] error.code=MISSING_HEADER': () => body?.error?.code === 'MISSING_HEADER',
        '[d3-05] message 가 X-User-Id 명시': () =>
            typeof body?.error?.message === 'string' && body.error.message.includes('X-User-Id'),
    });
}
