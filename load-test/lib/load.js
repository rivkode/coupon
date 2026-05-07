// Day 4 부하 시나리오 공통 헬퍼.
//
// 4 시나리오 (smoke / per-instance / real-scenario / spike) 가 공유하는 응답 분류 +
// 카운터 + 요약 출력 패턴. 각 시나리오의 요점은 부하 프로파일 (VU / 지속시간) 이고,
// 결과 분류 로직은 동일.
//
// 본 파일이 외부화하는 것:
//   - SUCCEEDED / FAILED / RATE_LIMITED / CIRCUIT_BREAKER / OTHER_ERROR 카운터
//   - HTTP 응답 → 카테고리 분류
//   - 요약 한 줄 (시나리오 종료 시 표준출력)
//
// 본 파일이 외부화하지 않는 것:
//   - 시나리오 thresholds / executor — 시나리오마다 의도가 달라 각자 보유.
//   - VU 의 sleep 간격 / userId 매핑 — 시나리오의 트래픽 모델 자체.

import { Counter } from 'k6/metrics';

/**
 * prefix 별 카운터 5종 생성.
 * 카운터 이름은 k6 metrics 명명 규칙을 따라 snake_case.
 */
export function makeCounters(prefix) {
    return {
        succeeded: new Counter(`${prefix}_succeeded`),
        failed: new Counter(`${prefix}_failed`),
        rateLimited: new Counter(`${prefix}_rate_limited`),
        circuitBreaker: new Counter(`${prefix}_circuit_breaker`),
        otherError: new Counter(`${prefix}_other_error`),
    };
}

/**
 * HTTP 응답 1건을 카테고리 분류 + 카운터 증가.
 *   200 + body.data.status=SUCCEEDED  → SUCCEEDED
 *   200 + body.data.status=FAILED      → FAILED         (SOLD_OUT / 정상 매진)
 *   200 + 그 외 status (PENDING 등)    → OTHER          (예상 외 상태 — 결함 / API 변경 신호)
 *   429                                  → RATE_LIMITED  (Bucket4j 사용자별 10 req/sec)
 *   503                                  → CIRCUIT_BREAKER (Resilience4j OPEN / 보상 후)
 *   그 외 (4xx/5xx, connection refused) → OTHER          (시스템 결함 또는 호스트 한계 신호)
 */
export function classifyAndCount(res, counters) {
    let body;
    try { body = res.json(); } catch (_) { body = null; }

    if (res.status === 200 && body && body.data) {
        if (body.data.status === 'SUCCEEDED') {
            counters.succeeded.add(1);
            return 'SUCCEEDED';
        }
        if (body.data.status === 'FAILED') {
            counters.failed.add(1);
            return 'FAILED';
        }
        counters.otherError.add(1);
        return 'OTHER';
    }
    if (res.status === 429) {
        counters.rateLimited.add(1);
        return 'RATE_LIMITED';
    }
    if (res.status === 503) {
        counters.circuitBreaker.add(1);
        return 'CIRCUIT_BREAKER';
    }
    counters.otherError.add(1);
    return 'OTHER';
}

/**
 * 시나리오 종료 시 표준 출력에 찍을 한 줄 요약.
 * Prometheus 의 시계열 + 본 한 줄로 사용자가 결과를 즉시 식별.
 */
export function summaryLine(label, prefix, metrics) {
    const get = (k) => (metrics[k] ? metrics[k].values.count : 0);
    const s = get(`${prefix}_succeeded`);
    const f = get(`${prefix}_failed`);
    const rl = get(`${prefix}_rate_limited`);
    const cb = get(`${prefix}_circuit_breaker`);
    const oe = get(`${prefix}_other_error`);
    const total = s + f + rl + cb + oe;
    // OTHER (200/429/503 외 응답 — 결함 신호) 의 비율. CB_503 / RATE_LIMITED 는 정상 보호이므로 분리.
    const otherRate = total > 0 ? (oe / total * 100).toFixed(2) : '0.00';
    const dur = metrics['http_req_duration'] ? metrics['http_req_duration'].values : {};
    const p50 = dur['med'] ? dur['med'].toFixed(1) : '?';
    const p95 = dur['p(95)'] ? dur['p(95)'].toFixed(1) : '?';
    const p99 = dur['p(99)'] ? dur['p(99)'].toFixed(1) : '?';

    return `\n[${label}] total=${total} SUCCEEDED=${s} FAILED=${f} RATE_LIMITED=${rl} CB_503=${cb} OTHER=${oe} (other_rate=${otherRate}%)\n` +
           `[${label}] http_req_duration p50=${p50}ms p95=${p95}ms p99=${p99}ms\n`;
}
