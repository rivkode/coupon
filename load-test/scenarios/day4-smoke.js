// Day 4 — Smoke (1 VU × 60s)
//
// 목적: warm-up + sanity. 정상 부하 (단일 사용자 10 req/s) 에서 p95/CPU/HikariCP 의 baseline.
// 다른 시나리오 (per-instance / real-scenario / spike) 의 결과를 해석하기 위한 비교 기준.
//
// 트래픽: 1 VU 가 100ms 간격으로 발급 요청 → ~10 req/sec → 60 s 동안 ~600 건.
//   - Bucket4j 사용자당 10 req/s 한계 — 의도적으로 임계 직하 (429 거의 없어야 함).
//   - 재고 (run-day4.sh 가 10,000 seed) 충분 — SOLD_OUT 거의 없어야 함.
//
// 통과 기준 (thresholds): p95 < 500ms, OTHER_ERROR == 0.
//   - p95 < 500ms 는 baseline 정상 동작의 보수적 임계 (실제 단일 사용자 환경이면 < 100ms 기대).
//   - 임계를 초과하면 시스템 자체의 cold start / 잔여 부하 / 인프라 결함 신호.

import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, ISSUE_ENDPOINT } from '../lib/env.js';
import { buildBody, buildHeaders, newIdempotencyKey } from '../lib/payload.js';
import { makeCounters, classifyAndCount, summaryLine } from '../lib/load.js';

const PREFIX = 'day4_smoke';
const counters = makeCounters(PREFIX);

export const options = {
    scenarios: {
        smoke: {
            executor: 'constant-vus',
            vus: 1,
            duration: '60s',
        },
    },
    thresholds: {
        // baseline — 단일 사용자가 system 의 정상 응답을 받아야 함.
        'http_req_duration': ['p(95)<500'],
        [`${PREFIX}_other_error`]: ['count==0'],
    },
};

export default function () {
    const userId = '40001';
    const idem = newIdempotencyKey();
    const res = http.post(`${BASE_URL}${ISSUE_ENDPOINT}`, buildBody(), {
        headers: buildHeaders(userId, idem),
    });
    classifyAndCount(res, counters);
    sleep(0.1); // 100ms 간격 = 10 req/sec — Bucket4j 임계 직하
}

export function handleSummary(data) {
    return { stdout: summaryLine('day4-smoke', PREFIX, data.metrics) };
}
