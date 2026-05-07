// Day 4 — Per-Instance Budget (100 VU × 3min, ~1,000 TPS 목표)
//
// 목적: CLAUDE.md §2 가 명시한 "인스턴스당 500 ~ 1,000 TPS" 의 상단 임계가 1 vCPU / 2 GB
// 환경에서 실제 충족 가능한지 측정. Day 5 사이징 (Little's Law) 의 입력 수치 산출.
//
// 트래픽: 100 VU × 100ms 간격 = 1,000 TPS sustained. 3 분 동안 ~180,000 요청.
//   - 사용자당 10 req/s — Bucket4j 임계 직하.
//   - 재고 (run-day4.sh 10,000 seed) 의 18 배 → SUCCEEDED 천장 = 10,000 (재고 권위).
//   - 나머지는 SOLD_OUT (FAILED) — 정상 매진 응답.
//
// 핵심 metric (Grafana 패널 동시 관측):
//   - http_req_duration p95 — 결정 트리 (server-a-tuning §3.3) 임계 200/500ms
//   - hikaricp_connections_active / pending — pool 포화 여부
//   - tomcat_threads_busy_threads — queue 포화 여부
//   - process_cpu_usage — 1 vCPU 헤드룸 (1.0 = 한계)
//
// 통과 기준 (thresholds): "통과" 보다 결정 신호.
//   - p95 < 500ms 는 server-a-tuning §3.3 의 "Phase C 미진입" 분기.
//   - p95 > 500ms → batch insert 도입 후보 신호 (보고서에 측정값 기록).
//   - OTHER_ERROR == 0 — 200/429/503 외 응답은 시스템 결함.

import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders, newIdempotencyKey } from '../lib/payload.js';
import { makeCounters, classifyAndCount, summaryLine } from '../lib/load.js';

const PREFIX = 'day4_per_instance';
const counters = makeCounters(PREFIX);

export const options = {
    scenarios: {
        per_instance: {
            executor: 'constant-vus',
            vus: 100,
            duration: '3m',
        },
    },
    thresholds: {
        // 결정 신호 — fail 해도 시나리오 자체는 완료. 보고서가 임계 초과 여부를 기록.
        'http_req_duration': ['p(95)<500'],
        [`${PREFIX}_other_error`]: ['count==0'],
    },
};

export default function () {
    // VU 별 사용자 격리 — 1 ~ 100 user.
    const userId = `${40100 + __VU}`;
    const idem = newIdempotencyKey();
    const res = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), {
        headers: buildHeaders(userId, idem),
    });
    classifyAndCount(res, counters);
    sleep(0.1); // 100ms = 10 req/s/user × 100 user = 1,000 TPS
}

export function handleSummary(data) {
    return { stdout: summaryLine('day4-per-instance', PREFIX, data.metrics) };
}
