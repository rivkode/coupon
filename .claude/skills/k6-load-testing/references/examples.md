# k6 실전 예시 — 인증, CSV 시드, 단계별 ramp

복잡한 시나리오를 위한 패턴 모음.

---

## 1. 인증 토큰을 setup() 에서 1회 발급해 공유

```js
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
  // 1회 실행 — 모든 VU 가 공유
  const res = http.post(`${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: 'loadtest@example.com', password: 'password' }),
    { headers: { 'Content-Type': 'application/json' } });

  if (res.status !== 200) {
    throw new Error(`setup login failed: ${res.status}`);
  }
  return { token: res.json('accessToken') };
}

export default function (data) {
  // data.token 은 모든 VU 가 같은 값을 받음
  const headers = { Authorization: `Bearer ${data.token}` };
  http.get(`${BASE_URL}/api/v1/me`, { headers });
}
```

---

## 2. 사용자별 다른 토큰 (CSV 시드)

```js
import { SharedArray } from 'k6/data';
import http from 'k6/http';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

const users = new SharedArray('users', function () {
  return papaparse.parse(open('../data/users.csv'), { header: true }).data;
});

export function setup() {
  // 각 사용자에 대해 토큰 발급해 배열로 반환
  const tokens = users.map(u => {
    const res = http.post(`${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({ email: u.email, password: u.password }),
      { headers: { 'Content-Type': 'application/json' } });
    return { email: u.email, token: res.json('accessToken') };
  });
  return { tokens };
}

export default function (data) {
  // VU 마다 다른 토큰 선택
  const user = data.tokens[(__VU - 1) % data.tokens.length];
  const headers = { Authorization: `Bearer ${user.token}` };
  http.get(`${BASE_URL}/api/v1/me`, { headers });
}
```

`k6/data/users.csv`:
```csv
email,password
loadtest1@example.com,password
loadtest2@example.com,password
loadtest3@example.com,password
```

---

## 3. 단계별 ramp + 도착률 모드

### 3.1 stages (VU 수 기준)

```js
export const options = {
  stages: [
    { duration: '30s', target: 10 },    // 0 → 10
    { duration: '1m',  target: 50 },    // 10 → 50
    { duration: '5m',  target: 50 },    // 50 유지
    { duration: '1m',  target: 100 },   // 50 → 100 (stress)
    { duration: '2m',  target: 100 },   // 100 유지
    { duration: '30s', target: 0 },     // ramp-down
  ],
};
```

### 3.2 도착률 (RPS) 기준

```js
export const options = {
  scenarios: {
    constant_rps: {
      executor: 'constant-arrival-rate',
      rate: 100,                  // 초당 100 요청
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
};
```

> "초당 N 요청" 명세에는 `constant-arrival-rate`. 단순 VU 고정은 응답 시간이 늘면 RPS 가 떨어진다.

### 3.3 ramping-arrival-rate

```js
scenarios: {
  ramp_rps: {
    executor: 'ramping-arrival-rate',
    startRate: 10,
    timeUnit: '1s',
    preAllocatedVUs: 100,
    maxVUs: 500,
    stages: [
      { duration: '1m', target: 50 },
      { duration: '5m', target: 200 },
      { duration: '1m', target: 0 },
    ],
  },
},
```

---

## 4. 시나리오 분리 (읽기 70% / 쓰기 30%)

```js
export const options = {
  scenarios: {
    reads: {
      executor: 'constant-arrival-rate',
      rate: 70, timeUnit: '1s', duration: '5m',
      preAllocatedVUs: 30, maxVUs: 100,
      exec: 'readScenario',
    },
    writes: {
      executor: 'constant-arrival-rate',
      rate: 30, timeUnit: '1s', duration: '5m',
      preAllocatedVUs: 30, maxVUs: 100,
      exec: 'writeScenario',
    },
  },
};

export function readScenario() { /* GET 요청 */ }
export function writeScenario() { /* POST 요청 */ }
```

---

## 5. 응답 본문 검증 + 동적 자원 ID

```js
import http from 'k6/http';
import { check } from 'k6';

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}`, 'Content-Type': 'application/json' };

  // 1. 주문 생성
  const create = http.post(`${BASE_URL}/api/v1/orders`,
    JSON.stringify({ productId: 'P1', quantity: 1 }),
    { headers, tags: { endpoint: 'create-order' } });

  check(create, {
    'create 201': (r) => r.status === 201,
  });
  if (create.status !== 201) return;

  const orderId = create.json('orderId');

  // 2. 즉시 조회
  const get = http.get(`${BASE_URL}/api/v1/orders/${orderId}`,
    { headers, tags: { endpoint: 'get-order' } });

  check(get, {
    'get 200':            (r) => r.status === 200,
    'matches orderId':    (r) => r.json('orderId') === orderId,
    'status PLACED':      (r) => r.json('status') === 'PLACED',
  });

  // 3. 취소
  const cancel = http.post(`${BASE_URL}/api/v1/orders/${orderId}/cancel`,
    JSON.stringify({ reason: 'CUSTOMER_REQUEST' }),
    { headers, tags: { endpoint: 'cancel-order' } });

  check(cancel, { 'cancel 200': (r) => r.status === 200 });
}
```

---

## 6. 실패 케이스만 로깅 (콘솔 폭주 방지)

```js
import http from 'k6/http';

export default function () {
  const res = http.get(`${BASE_URL}/api/v1/orders`);
  if (res.status >= 400) {
    console.error(`status=${res.status} body=${res.body}`);
  }
}
```

---

## 7. 결과 후처리 (JSON → 분석)

```bash
k6 run --out json=k6/results/load.json k6/scripts/load.js

# jq 로 핵심 메트릭 추출
jq '.data | select(.metric=="http_req_duration") | .value' k6/results/load.json | head
```

또는 `--summary-export` 로 요약:
```bash
k6 run --summary-export=k6/results/summary.json k6/scripts/load.js
```

`summary.json` 의 핵심 필드:
- `metrics.http_req_duration.values."p(95)"`
- `metrics.http_req_failed.values.rate`
- `metrics.http_reqs.values.rate`

이 요약은 PR / README 에 첨부 가능한 크기.

---

## 8. 데이터 격리 (VU 별 다른 자원)

부하 테스트가 운영 데이터를 오염시키지 않도록:

```js
export default function (data) {
  // VU 식별자를 자원에 포함 — 추후 일괄 삭제 가능
  const productId = `LOADTEST-VU${__VU}-IT${__ITER}`;
  http.post(`${BASE_URL}/api/v1/products`,
    JSON.stringify({ id: productId, name: 'load test product' }),
    { headers });
}

export function teardown(data) {
  // 모든 LOADTEST- 접두 자원 정리
  http.del(`${BASE_URL}/api/v1/admin/products?prefix=LOADTEST-`,
    null, { headers: { Authorization: `Bearer ${data.token}` } });
}
```
