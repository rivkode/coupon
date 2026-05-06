// Day 2 Scenario 4 — Burst (통합 모드, e2e 정합성 검증)
//
// 목적: server-a → server-b 실 통합 흐름에서 SUCCEEDED + FAILED + 재고 권위가 일관됨을 검증.
// 본격 부하 / 1 vCPU 시뮬레이션은 Day 4 k6 capacity-planning 의 영역. 본 시나리오는 작은 부하로
// "ISSUED + FAILED + RATE_LIMITED == 처리된 응답 수, errors 0" 만 확인.
//
// 사전 조건:
//   - server-a (8080, default profile, RestClient 활성, base-url=8081), server-b (8081), redis, mysql
//   - redis 의 event 1 재고 100 seed (run-day2-integrated.sh 가 자동):
//       for i in 0 1 2 3 4 5 6 7 8 9; do
//         docker exec promotion-redis redis-cli SET "event:1:stock:$i" 10
//       done
//
// 시나리오: 50 VU × 4 iter = 200 req, 각 VU 가 다른 user.
// 기대:
//   - 200 OK 응답 비율 >= 0.5 (CB OPEN 으로 일부 503 가능 — 200ms timeout 환경)
//   - SUCCEEDED <= 100 (재고 권위)
//   - SUCCEEDED + FAILED + RATE_LIMITED + CIRCUIT_BREAKER == 200 (errors 0)
//
// 실행: ./load-test/run-day2-integrated.sh

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders, newIdempotencyKey } from '../lib/payload.js';

// 환경변수로 외부화 — run-day2-integrated.sh 의 stock seed 와 동기화. 기본값은 일치하는 매직 넘버.
const STOCK = Number(__ENV.STOCK || 100);
const WARMUP = Number(__ENV.WARMUP || 5);
// SUCCEEDED 천장: setup warmup 5 건이 재고 5 차감 → main 의 SUCCEEDED 는 STOCK - WARMUP 이하.
const SUCCEEDED_CEILING = STOCK - WARMUP;

const succeededCounter = new Counter('day2_burst_succeeded');
const failedCounter = new Counter('day2_burst_failed');
const rateLimitedCounter = new Counter('day2_burst_rate_limited');
const circuitBreakerCounter = new Counter('day2_burst_circuit_breaker');
const otherErrorCounter = new Counter('day2_burst_other_error');

export const options = {
    scenarios: {
        burst: {
            executor: 'per-vu-iterations',
            vus: 50,
            iterations: 4,
            maxDuration: '60s',
        },
    },
    thresholds: {
        // 200/429/503 외의 응답 (validation 실패 등) 이 발생하면 시스템 결함.
        'day2_burst_other_error': ['count==0'],
        // 재고 권위 — SUCCEEDED 가 (stock - warmup) 천장을 초과해서는 안 됨.
        // run-day2-integrated.sh 가 STOCK / WARMUP 환경변수를 주입하지 않으면 기본값 100/5.
        'day2_burst_succeeded': [`count<=${SUCCEEDED_CEILING}`],
    },
};

// CB 의 sliding-window 가 정상 응답으로 채워지도록 main 부하 전 WARMUP 번 warmup.
// JVM cold start + RestClient connection pool 초기화 비용을 흡수.
export function setup() {
    for (let i = 0; i < WARMUP; i++) {
        const userId = `${999000 + i}`;
        const idem = newIdempotencyKey();
        http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), {
            headers: buildHeaders(userId, idem),
        });
        sleep(0.3);
    }
}

export default function () {
    // VU 별 사용자 격리. X-User-Id 는 Long 이라 숫자만.
    const userId = `${20000 + __VU}`;
    const idem = newIdempotencyKey();
    const headers = buildHeaders(userId, idem);
    const res = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), { headers });

    let body;
    try { body = res.json(); } catch (_) { body = null; }

    if (res.status === 200 && body && body.data) {
        if (body.data.status === 'SUCCEEDED') {
            succeededCounter.add(1);
        } else if (body.data.status === 'FAILED') {
            failedCounter.add(1);
        } else {
            otherErrorCounter.add(1);
        }
    } else if (res.status === 429) {
        rateLimitedCounter.add(1);
    } else if (res.status === 503) {
        // 보상 후 / Circuit Breaker OPEN — 정상 보호 동작.
        circuitBreakerCounter.add(1);
    } else {
        otherErrorCounter.add(1);
    }

    check(res, {
        '[d2-04] status 200/429/503': (r) => r.status === 200 || r.status === 429 || r.status === 503,
    });
}

export function handleSummary(data) {
    const m = data.metrics;
    const issued = m.day2_burst_succeeded ? m.day2_burst_succeeded.values.count : 0;
    const failed = m.day2_burst_failed ? m.day2_burst_failed.values.count : 0;
    const rl = m.day2_burst_rate_limited ? m.day2_burst_rate_limited.values.count : 0;
    const cb = m.day2_burst_circuit_breaker ? m.day2_burst_circuit_breaker.values.count : 0;
    const errors = m.day2_burst_other_error ? m.day2_burst_other_error.values.count : 0;
    const total = issued + failed + rl + cb + errors;
    const summary = `\n[d2-04 burst] SUCCEEDED=${issued} FAILED=${failed} RATE_LIMITED=${rl} CIRCUIT_BREAKER_503=${cb} OTHER_ERROR=${errors} TOTAL=${total} (stock=${STOCK} warmup=${WARMUP} ceiling=${SUCCEEDED_CEILING})\n`;
    return { stdout: summary };
}
