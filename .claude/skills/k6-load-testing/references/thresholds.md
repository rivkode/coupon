# thresholds 매핑표 — SLA → k6 옵션

과제 명세에 등장하는 표현을 그대로 thresholds 로 변환하기 위한 빠른 참조.

---

## 1. 응답 시간

| 명세 표현 | k6 thresholds |
|---|---|
| "평균 응답 시간 < 200ms" | `http_req_duration: ['avg<200']` (권장 X — outlier 무시됨) |
| "p95 < 300ms" | `http_req_duration: ['p(95)<300']` |
| "p99 < 800ms" | `http_req_duration: ['p(99)<800']` |
| "최대 응답 < 2초" | `http_req_duration: ['max<2000']` (소규모일 때만) |
| "TP99 < 1초" | `http_req_duration: ['p(99)<1000']` |
| "응답 시간 95% 이상이 500ms 이내" | `http_req_duration: ['p(95)<500']` |

> `avg` 는 outlier 에 무뎌서 SLA 표현으로 부적절. 가능하면 백분위(p95/p99) 사용.

---

## 2. 에러율 / 가용성

| 명세 표현 | k6 thresholds |
|---|---|
| "성공률 99% 이상" | `http_req_failed: ['rate<0.01']` |
| "에러율 0.5% 이하" | `http_req_failed: ['rate<0.005']` |
| "5xx 0건" | `http_req_failed: ['rate<0.001']` (실질적으로 0 근사) |
| "checks 통과율 99%" | `checks: ['rate>0.99']` |

---

## 3. 처리량 (Throughput)

| 명세 표현 | k6 thresholds |
|---|---|
| "초당 100 요청 처리" | `http_reqs: ['rate>100']` |
| "분당 6000 요청" | `http_reqs: ['count>6000']` (1분 시나리오 기준) |
| "RPS 200 이상 유지" | `http_reqs: ['rate>200']` |

> rate 는 시나리오 전체 평균. 단계별 rate 가 필요하면 `stages` + 외부 모니터링 병행.

---

## 4. 동시 접속 / VU

| 명세 표현 | k6 옵션 |
|---|---|
| "동시 사용자 100명" | `vus: 100` 고정 또는 `stages` 의 target |
| "100명까지 단계적 증가" | `stages: [{ duration: '2m', target: 100 }]` |
| "최대 1000명 부하" | `stages` 의 max target = 1000 |
| "초당 200건 신규 도착" (도착률) | `executor: 'constant-arrival-rate', rate: 200, timeUnit: '1s'` |

> "동시 사용자" 와 "초당 도착" 은 다르다. 명세를 잘 읽고 매핑.

---

## 5. 엔드포인트별 (sub-threshold)

```js
thresholds: {
  'http_req_duration{endpoint:create-order}':  ['p(95)<400'],
  'http_req_duration{endpoint:get-order}':     ['p(95)<150'],
  'http_req_duration{endpoint:list-orders}':   ['p(95)<250'],
  'http_req_failed{endpoint:create-order}':    ['rate<0.005'],
}
```

요청에 `tags: { endpoint: 'create-order' }` 를 붙여야 매칭됨.

---

## 6. 커스텀 메트릭

```js
import { Trend, Counter, Rate } from 'k6/metrics';

const orderCreateDuration = new Trend('order_create_duration');
const orderFailures       = new Counter('order_failures');
const orderSuccessRate    = new Rate('order_success_rate');

export const options = {
  thresholds: {
    order_create_duration: ['p(95)<400'],
    order_failures:        ['count<10'],
    order_success_rate:    ['rate>0.99'],
  },
};
```

---

## 7. abortOnFail (선택)

특정 thresholds 위반 시 즉시 중단:
```js
thresholds: {
  http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '1m' }],
}
```

> 과제 전형에서는 보통 사용 X. 부분 결과를 봐야 디버깅 가능.

---

## 8. SLA 가 명시되지 않은 경우 (기본값 권장)

| 메트릭 | 기본 임계 | 비고 |
|---|---|---|
| `http_req_failed` | `rate<0.01` | 1% 에러까지 허용 |
| `http_req_duration` p95 | `<300` | 일반 CRUD 기준 |
| `http_req_duration` p99 | `<800` | tail latency |
| `checks` | `rate>0.99` | 응답 본문 검증 |

이 값을 README 의 "성능 기준" 섹션에 명시하고 트레이드오프 작성.
