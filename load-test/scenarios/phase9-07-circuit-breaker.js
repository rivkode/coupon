// Phase 9 Scenario 7 — Circuit Breaker 시뮬레이션
//
// **사전 조건**: Server A 를 default profile (no `local`) 로 기동하여 RestClient 활성화 +
//              Server B 가 기동되지 않은 상태. (자세한 절차는 load-test/README.md 참조.)
//
// 기대:
//   - 12회 호출 모두 503 + Retry-After: 5
//   - 마지막 직전에 CB 가 OPEN 상태로 전환 (sliding-window 20, min-calls 10, fail-rate 50%)
//   - /actuator/circuitbreakers 응답에서 state == OPEN 확인

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT, ACTUATOR_CB_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders } from '../lib/payload.js';

const CALLS = 12;

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
    const responses = [];

    for (let i = 0; i < CALLS; i++) {
        const headers = buildHeaders(`9700${i}`); // 매 호출 다른 user → Rate Limit 우회
        responses.push(http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), { headers }));
    }

    const allFailed = responses.every((r) => r.status === 503);
    const last = responses[responses.length - 1];

    const cbRes = http.get(`${BASE_URL}${ACTUATOR_CB_ENDPOINT}`);
    let cbState = null;
    try {
        const cb = cbRes.json();
        cbState = cb && cb.circuitBreakers && cb.circuitBreakers.couponIssuing && cb.circuitBreakers.couponIssuing.state;
    } catch (_) { /* ignore */ }

    check(null, {
        [`[07] 12회 모두 HTTP 503`]: () => allFailed,
        '[07] 마지막 응답 Retry-After == 5': () => last && header(last, 'Retry-After') === '5',
        '[07] /actuator/circuitbreakers 200': () => cbRes.status === 200,
        '[07] CB state == OPEN': () => cbState === 'OPEN',
    });
}
