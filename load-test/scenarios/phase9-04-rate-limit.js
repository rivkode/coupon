// Phase 9 Scenario 4 — Rate Limit (사용자당 10 req/sec, ADR-005)
//
// 기대: 1초 안에 15회 호출하면 처음 10건 = 200, 11~15번째 = 429.
//      429 응답은 Retry-After 헤더 + RATE_LIMIT_EXCEEDED 코드.

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders } from '../lib/payload.js';

const TOTAL_CALLS = 15;
const ALLOWED = 10;

function header(res, name) {
    const target = name.toLowerCase();
    for (const k of Object.keys(res.headers || {})) {
        if (k.toLowerCase() === target) return res.headers[k];
    }
    return undefined;
}

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        checks: ['rate==1.0'],
    },
};

export default function () {
    const userId = '9401';
    const responses = [];

    for (let i = 0; i < TOTAL_CALLS; i++) {
        const headers = buildHeaders(userId);
        responses.push(http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), { headers }));
    }

    const successes = responses.filter((r) => r.status === 200).length;
    const rateLimited = responses.filter((r) => r.status === 429).length;
    const last = responses[responses.length - 1];

    check(null, {
        [`[04] 200 응답 == ${ALLOWED}건`]: () => successes === ALLOWED,
        [`[04] 429 응답 == ${TOTAL_CALLS - ALLOWED}건`]: () => rateLimited === TOTAL_CALLS - ALLOWED,
        '[04] 마지막 응답 status == 429': () => last && last.status === 429,
        // 주의: k6 (Go net/http) 가 응답 헤더 키를 canonical MIME 형태로 정규화한다
        //       (예: X-RateLimit-Remaining → X-Ratelimit-Remaining). header() 헬퍼로 case-insensitive 조회.
        '[04] 마지막 응답 Retry-After 헤더 존재': () => last && header(last, 'Retry-After') !== undefined,
        '[04] 마지막 응답 X-RateLimit-Remaining == 0': () => last && header(last, 'X-RateLimit-Remaining') === '0',
        '[04] 429 body code == RATE_LIMIT_EXCEEDED': () => {
            try {
                const b = last.json();
                return b && b.error && b.error.code === 'RATE_LIMIT_EXCEEDED';
            } catch (_) { return false; }
        },
    });
}
