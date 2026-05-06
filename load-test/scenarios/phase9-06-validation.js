// Phase 9 Scenario 6 — body validation 실패 (eventId 누락)
//
// 기대: 400 + body.error.code == VALIDATION_FAILED + fieldErrors 에 eventId.

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildHeaders } from '../lib/payload.js';

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        checks: ['rate==1.0'],
    },
};

export default function () {
    // eventId 를 의도적으로 빼고 보낸다.
    const invalidBody = JSON.stringify({
        deviceId: 'k6',
        channel: 'WEB',
        requestedAt: new Date().toISOString(),
        clientVersion: '1.0',
        region: 'KR',
        language: 'ko',
        marketingConsent: true,
    });
    const res = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, invalidBody, { headers: buildHeaders('9601') });

    let body;
    try { body = res.json(); } catch (_) { body = null; }

    check(res, { '[06] HTTP 400': (r) => r.status === 400 });
    check(null, {
        '[06] success == false': () => body && body.success === false,
        '[06] error.code == VALIDATION_FAILED': () => body && body.error && body.error.code === 'VALIDATION_FAILED',
        '[06] fieldErrors 가 eventId 포함': () => {
            const fes = body && body.error && body.error.fieldErrors;
            return Array.isArray(fes) && fes.some((fe) => fe.field === 'eventId');
        },
    });
}
