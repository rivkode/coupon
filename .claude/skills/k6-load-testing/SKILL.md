---
name: k6-load-testing
description: 본 과제 성능 검증 스킬. CLAUDE.md §8 기준 디렉토리 (`load-test/scenarios/`), 시나리오 분류(스모크/로드/스트레스/스파이크), thresholds 설계 (p95/p99/error_rate), Idempotency-Key 주입, 환경변수 주입(BASE_URL), 결과 해석을 포함한다. "k6", "부하 테스트", "성능 테스트", "load test", "stress test" 키워드가 나오거나 평가 항목 ⑤ (사이징) 검증 시 PROACTIVELY 사용. 미충족 시 `concurrency` 또는 `cache-strategy` 로 회귀.
---

# k6 Load Testing (promotion)

본 스킬은 CLAUDE.md §3 ⑤ (인프라 사이징) + §12 Day 4 (부하 테스트) 에 직접 대응한다.

> JUnit 통합 테스트가 "기능이 맞다" 를 증명한다면, k6 는 "10,000 TPS 트래픽 아래에서도 맞다" 를 증명한다.

---

## 1. 본 과제 시나리오 우선순위

CLAUDE.md §3 ① (대량 트래픽) 과 ⑤ (사이징) 모두 부하 검증을 요구. 5일 일정에서:

| 우선 | 시나리오 | 목적 |
|---|---|---|
| Must | smoke | CI/매 커밋 — 동작 확인 |
| Must | load | Server A 단일 노드 500~1,000 TPS 측정 |
| Must | spike | 선착순 매진 시나리오 (10,000장 즉시 sold-out) |
| Nice | stress | 단일 노드 한계 탐색 |
| Skip | soak | 5일 일정에 어울리지 않음 — README 한 줄 |

---

## 2. 디렉토리 구조 (CLAUDE.md §8)

```
promotion/
└── load-test/
    └── scenarios/
        ├── smoke.js           ← VU 1, 1분
        ├── load.js            ← 정상 트래픽 측정
        ├── spike.js           ← 선착순 매진 시나리오
        └── stress.js          ← 한계 탐색 (선택)
    ├── lib/
    │   ├── auth.js            ← user_id 헤더 헬퍼
    │   ├── checks.js
    │   └── env.js
    ├── data/                  ← (선택) 사용자 시드
    └── results/               ← .gitignore (요약 JSON 만 PR 첨부)
```

---

## 3. 표준 thresholds

### 3.1 발급 (issue) 시나리오

```js
export const options = {
  thresholds: {
    http_req_failed:                                   ['rate<0.01'],
    http_req_duration:                                 ['p(95)<300', 'p(99)<800'],
    'http_req_duration{endpoint:issue}':               ['p(95)<300'],
    'http_req_duration{endpoint:issue,outcome:200}':   ['p(95)<300'],
    'http_req_duration{endpoint:issue,outcome:409}':   ['p(95)<200'],   // sold out 은 빨라야
    checks:                                            ['rate>0.99'],
  },
};
```

### 3.2 매진 시나리오 (spike)

```js
export const options = {
  thresholds: {
    http_req_failed:    ['rate<0.05'],   // 5% 이내 (429/503/504 포함)
    http_reqs:          ['count>=20000'],// 최소 20k 요청 발생
    'checks{name:status_acceptable}':  ['rate>0.99'],   // 200/409/429/503 정상
  },
  scenarios: {
    burst: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 500,
      stages: [
        { duration: '10s', target: 1000 },   // 0 → 1000 TPS 빠르게 ramp-up
        { duration: '30s', target: 1000 },
      ],
    },
  },
};
```

---

## 4. 표준 스크립트 골격 — issue.js

```js
// load-test/scenarios/load.js
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from '../lib/uuid.js';
import { BASE_URL, USER_POOL_SIZE } from '../lib/env.js';

export const options = {
  stages: [
    { duration: '30s', target: 50 },
    { duration: '3m',  target: 50 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed:                       ['rate<0.01'],
    'http_req_duration{endpoint:issue}':   ['p(95)<300', 'p(99)<800'],
    checks:                                ['rate>0.99'],
  },
};

export default function () {
  const userId = `u${__VU}-${__ITER}`;       // VU + iteration 으로 고유
  const headers = {
    'X-User-Id': userId,
    'Idempotency-Key': uuidv4(),
    'Content-Type': 'application/json',
  };

  const body = JSON.stringify({ eventId: 'concert-2026' });

  const res = http.post(`${BASE_URL}/api/v1/coupons/issue-requests`, body,
    { headers, tags: { endpoint: 'issue' } });

  check(res, {
    'status acceptable':       (r) => [200, 409, 429].includes(r.status),
    'has body':                (r) => r.body && r.body.length > 0,
    'has X-Request-Id header': (r) => r.headers['X-Request-Id'] !== undefined,
  });
}
```

---

## 5. 매진(spike) 시나리오 — 선착순 검증

```js
// load-test/scenarios/spike.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from '../lib/uuid.js';
import { BASE_URL } from '../lib/env.js';

const issued = new Counter('coupons_issued');
const soldOut = new Counter('coupons_sold_out');
const rateLimited = new Counter('rate_limited');

export const options = {
  scenarios: {
    burst: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 500,
      stages: [
        { duration: '10s', target: 1000 },
        { duration: '30s', target: 1000 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    'checks{name:status_acceptable}': ['rate>0.99'],
  },
};

export default function () {
  const userId = `u${__VU}-${__ITER}`;
  const headers = {
    'X-User-Id': userId,
    'Idempotency-Key': uuidv4(),
    'Content-Type': 'application/json',
  };

  const res = http.post(`${BASE_URL}/api/v1/coupons/issue-requests`,
    JSON.stringify({ eventId: 'concert-2026' }),
    { headers, tags: { endpoint: 'issue' } });

  check(res, {
    'status_acceptable': (r) => [200, 409, 429, 503, 504].includes(r.status),
  });

  if (res.status === 200) issued.add(1);
  else if (res.status === 409) soldOut.add(1);
  else if (res.status === 429) rateLimited.add(1);
}

export function handleSummary(data) {
  return {
    'load-test/results/spike-summary.json': JSON.stringify({
      issued: data.metrics.coupons_issued,
      soldOut: data.metrics.coupons_sold_out,
      rateLimited: data.metrics.rate_limited,
      p95: data.metrics.http_req_duration.values['p(95)'],
      errorRate: data.metrics.http_req_failed.values.rate,
    }, null, 2),
    stdout: textSummary(data),
  };
}
```

**검증할 것**:
- `coupons_issued` 가 정확히 **10,000** (CLAUDE.md §1 가정)
- `coupons_sold_out` 으로 매진 응답 카운트
- `rate_limited` 가 폭주에서 적절히 발생 (Bucket4j 동작 확인)
- error_rate (5xx) < 5%

---

## 6. 실행 명령

```bash
# smoke
k6 run load-test/scenarios/smoke.js

# load (단일 노드 측정)
k6 run -e BASE_URL=http://localhost:8080 \
       --out json=load-test/results/load.json \
       load-test/scenarios/load.js

# spike (선착순)
k6 run -e BASE_URL=http://localhost:8080 \
       --summary-export=load-test/results/spike-summary.json \
       load-test/scenarios/spike.js
```

Makefile 단축:

```makefile
.PHONY: smoke load spike

smoke:
	k6 run load-test/scenarios/smoke.js

load:
	k6 run -e BASE_URL=$${BASE_URL:-http://localhost:8080} \
	       --out json=load-test/results/load.json \
	       load-test/scenarios/load.js

spike:
	k6 run -e BASE_URL=$${BASE_URL:-http://localhost:8080} \
	       --summary-export=load-test/results/spike-summary.json \
	       load-test/scenarios/spike.js
```

---

## 7. 결과 해석 — 무엇을 보고 어디로 회귀

| 증상 | 의심 | 회귀 |
|---|---|---|
| `p(95)` 초과, 정상 응답 | DB 쿼리 / 인덱스 / N+1 | `concurrency/SKILL.md` §7 §8 |
| `error_rate` 급증 (500 폭주) | 트랜잭션 / 락 경합 | `concurrency/SKILL.md` §2 §4 |
| 매진 후에도 200 응답 발생 | 재고 race condition | `concurrency/SKILL.md` §2 (Lua atomic) |
| 매진 발급 수가 10,000 ≠ | shard 합산 오류 또는 race | shard 계산 / Lua script 검증 |
| 429 가 너무 많이 발생 | Bucket4j 한계 너무 빠듯 | `rate-limiting-backpressure/SKILL.md` §2 |
| 504 / Circuit Breaker OPEN | A→B timeout 빈발 | `rate-limiting-backpressure/SKILL.md` §3 |
| Redis 단일 노드 CPU 100% | Hot Key | `cache-strategy/SKILL.md` §1 (sharding) |
| spike 직후 점진적 악화 | 메모리 / 커넥션 누수 | heap dump, HikariCP leak detection |
| `checks` 율 떨어짐 | 응답 형식 변경 | API 응답 검증 |

응답 시간 그래프 + summary JSON 을 README §9 에 첨부하면 평가자에게 강한 신호.

---

## 8. 자가 검증 체크리스트

- [ ] 모든 시나리오에 `thresholds` 가 있는가?
- [ ] `BASE_URL` 등 환경 의존 값이 `__ENV` 로 주입 가능?
- [ ] 매 요청에 **고유 Idempotency-Key** 가 생성 (재시도 테스트는 별도)?
- [ ] check 가 status 외에 **본문 키 / 헤더** 한 개 이상 검증?
- [ ] `tags` 로 엔드포인트별 측정 가능?
- [ ] 결과 저장 경로 (`results/`) 가 `.gitignore` 에 있고, summary JSON 만 PR 에 첨부 계획?
- [ ] Spike 시나리오에서 **발급 카운트 = 10,000** 검증?
- [ ] 429/503/504 응답을 정상으로 인정 (5xx 폭주만 실패)?
- [ ] Server A / B / C 가 docker-compose 로 띄워진 상태에서 측정?
- [ ] HTTPS 종단 측정 (운영과 일치)?
- [ ] 워밍업 30s 후 측정?

---

## 9. 안티패턴 (CLAUDE.md §10 회귀)

| 안티패턴 | 문제 | 교정 |
|---|---|---|
| `vus: 1000` 고정 한 줄 | 실 트래픽 형태 미반영 | `stages` ramp 또는 `ramping-arrival-rate` |
| Idempotency-Key 매 요청 동일 | 캐시 hit 만 테스트 | `uuidv4()` 매 요청 |
| `thresholds` 없음 | 통과/실패 자동 판정 불가 | 모든 시나리오에 thresholds (CLAUDE.md §10 직접 위반) |
| `check` 가 status 만 검증 | 200 빈 본문 통과 | 본문 키/헤더 검증 |
| 외부 인터넷 시드 | 회사 망 재현 불가 | `data/users.csv` 로컬 |
| 로컬 머신에서 운영 부하 측정 | 클라이언트 병목 | k6 외부 머신 |
| HTTP 측정 (운영 HTTPS) | TLS overhead 30~50% 누락 | HTTPS |
| 워밍업 없이 측정 | JIT/cache 미반영 | 30s 워밍업 |
| 결과 PR 미첨부 | 평가자가 검증 불가 | summary JSON 첨부 |

---

## 10. 다음 단계

- thresholds 미충족 → `concurrency/SKILL.md` (락 / N+1 / 인덱스)
- Hot Key 발견 → `cache-strategy/SKILL.md` (sharding)
- 진입 한계 도달 → `rate-limiting-backpressure/SKILL.md`
- 인스턴스 수 산식 → `capacity-planning/SKILL.md`
- 결과 README 정리 → README §9 (성능 검증)

상세 시나리오 → `references/scenarios.md`
SLA → thresholds 매핑 → `references/thresholds.md`
인증/시드/ramp 예시 → `references/examples.md`
