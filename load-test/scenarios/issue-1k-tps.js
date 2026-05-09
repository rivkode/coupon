// 인스턴스당 1000 TPS 발급 부하. 응답 모델은 즉시 "접수 완료" — p95/p99 latency 가 핵심 지표.
//
// 사전 조건:
//   docker compose up -d --build
//   curl http://localhost:8080/actuator/health   # UP
//
// 실행:
//   k6 run load-test/scenarios/issue-1k-tps.js
//   BASE_URL=http://localhost:8080 EVENT_ID=1 COUPON_TYPE_ID=1 k6 run ...

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders } from '../lib/payload.js';

const accepted = new Counter('issue_accepted');
const duplicate = new Counter('issue_duplicate');
const internalError = new Counter('issue_internal_error');
const otherError = new Counter('issue_other');

const EVENT_ID = Number(__ENV.EVENT_ID || 1);
const COUPON_TYPE_ID = Number(__ENV.COUPON_TYPE_ID || 1);

export const options = {
    // 1 vCPU 인스턴스에서 1000 TPS 목표 — 100 VU × 평균 ~10 req/s/VU.
    // 응답이 "접수 완료" 라 짧음 (Redis HSET + Kafka publish ~ 수 ms).
    scenarios: {
        steady_1k: {
            executor: 'constant-arrival-rate',
            rate: 1000,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 200,
            maxVUs: 500,
        },
    },
    thresholds: {
        // 99% 의 응답이 200 ms 이하. 1 vCPU + Redis + Kafka publish 의 자연 레이턴시 기준.
        http_req_duration: ['p(95)<200', 'p(99)<400'],
        // 4xx/5xx 0.5% 이하 — Circuit Breaker 정상 동작 시 일시적 503 만 허용.
        http_req_failed: ['rate<0.005'],
    },
};

export default function () {
    // VU 별 user_id — 1 인 1 장 제약을 만족시키기 위해 매 호출마다 user_id 와 coupon_type_id 를 증가.
    // 실제 부하 모델은 사용자가 자기 한 번만 호출하지만, 부하 측정에는 다양한 (user, type) 조합으로
    // UNIQUE 미충돌을 유지한다.
    const userId = (__VU * 100000) + __ITER + 1;
    const headers = buildHeaders(userId);
    const body = buildBody(EVENT_ID, COUPON_TYPE_ID);

    const res = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, body, { headers });

    let parsed;
    try { parsed = res.json(); } catch (_) { parsed = null; }
    const status = parsed && parsed.data ? parsed.data.status : null;

    if (res.status === 200 && status === 'ACCEPTED') {
        accepted.add(1);
    } else if (res.status === 200 && status === 'DUPLICATE') {
        duplicate.add(1);
    } else if (res.status === 503) {
        internalError.add(1);
    } else {
        otherError.add(1);
    }

    check(res, {
        'status is 200 or 503': (r) => r.status === 200 || r.status === 503,
    });
}
