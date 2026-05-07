// Day 4 — Real Scenario (CLAUDE.md §2 정확) — 1,000 VU × 10s
//
// 목적: CLAUDE.md §2 가 명시한 "사용자 1,000명, 10초 내 사용자당 100건, 평균 10,000 TPS" 를
// 정확히 재현. 본 시나리오는 1 인스턴스가 시스템 전체 부하 (10,000 TPS) 를 받았을 때 어디에서
// 어떻게 무너지는지를 정량화 — Day 5 사이징 (몇 인스턴스 필요한가) 의 정량적 근거.
//
// 트래픽:
//   1,000 VU × 100 reqs/VU = 100,000 시도, 10 s 동안 = 평균 10,000 TPS.
//   각 VU 가 100ms 간격 (= 10 req/s/user) → Bucket4j 임계 정확히 도달 (일부 429 정상).
//   재고 10,000 seed → 정확히 stock 만큼 SUCCEEDED, 나머지 SOLD_OUT.
//
// 본 시나리오의 의도된 메시지 (평가 시그널):
//   "1 인스턴스가 10,000 TPS 를 처리한다" 는 주장이 아니다.
//   "1 인스턴스의 한계를 정량화 → N 인스턴스 필요" 의 근거 측정.
//   결과 해석은 docs/reports/01.load-test-results.md 에 기록.
//
// 예상 결과 (1 인스턴스, 1 vCPU / 2 GB):
//   - SUCCEEDED ≤ 10,000 (재고 권위)
//   - 대부분 RATE_LIMITED + CIRCUIT_BREAKER + FAILED + (잔여) OTHER_ERROR (timeout)
//   - p95 매우 높음 — 1 인스턴스의 한계
//   - HikariCP active=10 + pending 폭증 (pool 포화)
//   - Tomcat busy 가 max-threads 200 에 근접
//
// thresholds:
//   - 본 시나리오는 "측정 + 호스트 한계 식별" — fail/pass 판정이 의미를 갖는 항목은 단 하나.
//   - SUCCEEDED ≤ STOCK — 재고 권위 절대 위반 금지 (Lua atomic 결함 신호).
//   - OTHER 비율은 호스트 한계 발동 시 대량 발생 가능 — threshold 로 fail 시키면 "측정 자체"
//     가 통과 기준이 됨. 그래서 OTHER 임계는 두지 않고, 결과 분석은 보고서 §3.3 가 담당.

import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders, newIdempotencyKey } from '../lib/payload.js';
import { makeCounters, classifyAndCount, summaryLine } from '../lib/load.js';

const PREFIX = 'day4_real_scenario';
const counters = makeCounters(PREFIX);

const STOCK = Number(__ENV.STOCK || 10000);

export const options = {
    scenarios: {
        real: {
            executor: 'per-vu-iterations',
            vus: 1000,
            iterations: 100,    // 사용자당 100건 (CLAUDE.md §2)
            maxDuration: '60s', // 10s 가 목표지만 1 vCPU 환경의 timeout 흡수 여유 — 실제는 ~10~30s
        },
    },
    thresholds: {
        // 재고 권위 — 절대 위반 금지. SUCCEEDED 가 stock 을 초과하면 Lua atomic 결함.
        [`${PREFIX}_succeeded`]: [`count<=${STOCK}`],
        // OTHER 비율 임계는 두지 않는다 — 본 시나리오는 측정이 목적이고, 호스트 한계 발동 시
        // OTHER 가 대량 발생하는 것이 정상 결과 (보고서 §3.3.1). 분석은 보고서가 담당.
    },
};

export default function () {
    // VU 별 사용자 격리 — 1 ~ 1,000 user. Bucket4j 가 user 별 토큰 버킷 관리.
    const userId = `${50000 + __VU}`;
    const idem = newIdempotencyKey();
    const res = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), {
        headers: buildHeaders(userId, idem),
    });
    classifyAndCount(res, counters);
    sleep(0.1); // 100ms = 10 req/s/user — Bucket4j 임계 정확히 매핑
}

export function handleSummary(data) {
    return { stdout: summaryLine('day4-real-scenario', PREFIX, data.metrics) };
}
