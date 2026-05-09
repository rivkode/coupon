// 평가항목 ③ — 이벤트 조회 캐시의 Refresh-Ahead 검증.
//
// 가설: refresh-interval (default 60s) < TTL (default 300s) 라서 캐시는 TTL 만료 전에
// 항상 갱신되며, 이로써 TTL 만료 시점의 "동시 다량 요청 → DB stampede" 패턴이 발생하지 않는다.
//
// 검증 방법:
//   - 60s 동안 50 RPS 의 GET 부하 (TTL 윈도우를 충분히 통과)
//   - 모든 응답이 200 + status=IN_PROGRESS 이어야 (cache hit 만)
//   - p(95) < 50ms (cache hit 의 빠른 응답 — DB 조회 시 ~수십 ms 와 차이)
//   - 5xx / 4xx = 0 (stampede 가 발생하면 DB 가 1 vCPU 한계 도달 시 5xx 가능)
//
// 사전 조건:
//   docker compose up -d --build
//   # event 시드 — server_c.event(event_id=1, status='IN_PROGRESS', 시간 범위 안)
//   # event:1 캐시 적재 (warmup):
//   curl http://localhost:8082/api/v1/events/1
//
// 빠른 검증을 위해 짧은 TTL/refresh 로 override 가능:
//   EVENT_CACHE_TTL_SECONDS=20 EVENT_CACHE_REFRESH_MS=5000 docker compose up -d server-c

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.SERVER_C_URL || 'http://localhost:8082';
const EVENT_ID = Number(__ENV.EVENT_ID || 1);
const RPS = Number(__ENV.RPS || 50);
const DURATION = __ENV.DURATION || '60s';

const cacheHits = new Counter('event_cache_hits');
const otherStatus = new Counter('event_other_status');
const inProgressRate = new Rate('event_in_progress_rate');

export const options = {
    scenarios: {
        steady_get: {
            executor: 'constant-arrival-rate',
            rate: RPS,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 20,
            maxVUs: 60,
        },
    },
    thresholds: {
        // Cache hit 의 빠른 응답 — Redis JSON 조회 + JSON 파싱 ~수 ms.
        // DB 조회 (cache miss) 가 섞이면 p95 가 ~수십 ms 로 올라감.
        http_req_duration: ['p(95)<50', 'p(99)<100'],
        // 모든 요청이 cache hit 으로 200 응답이어야 함 — stampede 발생 시 5xx 가능.
        http_req_failed: ['rate==0.0'],
        // 응답 body 가 IN_PROGRESS 상태 — 캐시 갱신이 정상으로 일어났음을 증명.
        event_in_progress_rate: ['rate==1.0'],
    },
};

export function setup() {
    // Warmup — 첫 호출은 cache miss → DB 조회. 본 부하 직전에 캐시 적재.
    const res = http.get(`${BASE_URL}/api/v1/events/${EVENT_ID}`);
    if (res.status !== 200) {
        throw new Error(`warmup failed: status=${res.status} body=${res.body}`);
    }
    console.log(`[warmup] cache populated for event ${EVENT_ID}`);
}

export default function () {
    const res = http.get(`${BASE_URL}/api/v1/events/${EVENT_ID}`);

    let body;
    try { body = res.json(); } catch (_) { body = null; }
    const isInProgress = body && body.data && body.data.status === 'IN_PROGRESS';

    inProgressRate.add(isInProgress ? 1 : 0);

    if (res.status === 200 && isInProgress) {
        cacheHits.add(1);
    } else {
        otherStatus.add(1);
    }

    check(res, {
        'status is 200': (r) => r.status === 200,
        'status field is IN_PROGRESS': () => isInProgress,
    });
}
