// Day 4 — Spike (0 → 500 VU 10s ramp + 10s steady, 5s ramp-down)
// (의도는 1,000 VU 였으나 호스트 (colima/macOS) 한계로 의도적 축소 — 본문 §"VU 1,000 → 500" 참조)
//
// 목적: 사용자 1,000명이 10초 안에 동시 진입하는 burst 의 흡수 능력 측정.
// real-scenario.js 가 "지속 부하" 라면 본 시나리오는 "동시 진입" 의 모양.
//
// 트래픽 모델:
//   t=0 ~ 10s   : VU 0 → 500 ramp (linear). 평균 50 VU/s 추가.
//   t=10 ~ 20s  : 500 VU steady (각 100ms 간격 = 10 req/s/user) — 5,000 TPS sustained.
//   t=20 ~ 25s  : 500 → 0 ramp down (graceful — 잔여 in-flight 응답 회수).
//
// VU 1,000 → 500 으로 축소 사유: 호스트 (colima) 의 file descriptor / connection backlog 한계.
// real-scenario (1,000 VU per-vu-iterations) 는 per-VU 가 직렬 실행이라 동시 connection 이 그리
// 많지 않지만, ramping-vus 1,000 은 동시 1,000 connection 을 강요 → 호스트가 못 버팀.
// 본 시나리오의 의도는 "burst 흡수" 검증 — 500 VU 로도 1 인스턴스의 burst 반응을 충분히 측정.
//
// 핵심 관측:
//   - Tomcat queue (acceptCount default 100) 포화 시점 → 새 connection 거부 (TCP reset)
//   - Resilience4j CB OPEN 타이밍 + 회복 시간
//   - Bucket4j 의 user-scoped 토큰 burst 흡수 vs sustained drop
//   - JVM heap GC pause 빈도 (burst 시 객체 폭증)
//
// 실제 결과는 docs/reports/01.load-test-results.md 에 기록 — Phase B 결정 트리 적용 (백프레셔 도입 여부).
//
// thresholds:
//   - 측정 시나리오 — fail 정상. 임계는 결정 신호.
//   - SUCCEEDED ≤ STOCK 재고 권위 절대 위반 금지.

import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders, newIdempotencyKey } from '../lib/payload.js';
import { makeCounters, classifyAndCount, summaryLine } from '../lib/load.js';

const PREFIX = 'day4_spike';
const counters = makeCounters(PREFIX);

const STOCK = Number(__ENV.STOCK || 10000);

export const options = {
    scenarios: {
        spike: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 500 }, // ramp-up — 동시 진입 burst
                { duration: '10s', target: 500 }, // steady — sustained at peak
                { duration: '5s', target: 0 },    // ramp-down — graceful drain
            ],
            gracefulRampDown: '5s',
        },
    },
    thresholds: {
        // 재고 권위 — Lua atomic 결함이 아닌 한 절대 위반 금지.
        [`${PREFIX}_succeeded`]: [`count<=${STOCK}`],
        // burst 흡수가 정상이면 OTHER (timeout / connection refused) 가 일정 한도 — 보고서에서 비율 분석.
        [`${PREFIX}_other_error`]: [`count<10000`],
    },
};

export default function () {
    const userId = `${60000 + __VU}`;
    const idem = newIdempotencyKey();
    const res = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), {
        headers: buildHeaders(userId, idem),
    });
    classifyAndCount(res, counters);
    sleep(0.1); // 100ms = 10 req/s/user (steady 구간) — Bucket4j 임계 매핑
}

export function handleSummary(data) {
    return { stdout: summaryLine('day4-spike', PREFIX, data.metrics) };
}
