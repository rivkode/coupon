// Phase 9 Scenario 5 — Idempotency-Key 헤더 누락
//
// 기대: 400 + body.error.code == MISSING_HEADER.

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody } from '../lib/payload.js';

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        checks: ['rate==1.0'],
    },
};

export default function () {
    // X-User-Id 만 보내고 Idempotency-Key 누락.
    const headers = {
        'X-User-Id': '9501',
        'Content-Type': 'application/json',
    };
    const res = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), { headers });

    let body;
    try { body = res.json(); } catch (_) { body = null; }

    check(res, { '[05] HTTP 400': (r) => r.status === 400 });
    check(null, {
        '[05] success == false': () => body && body.success === false,
        '[05] error.code == MISSING_HEADER': () => body && body.error && body.error.code === 'MISSING_HEADER',
        '[05] error.message 에 Idempotency-Key 언급': () => body && body.error && /Idempotency-Key/i.test(body.error.message || ''),
    });
}
