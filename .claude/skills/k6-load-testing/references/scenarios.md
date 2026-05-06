# k6 시나리오 5종 — 완성된 스크립트 예시

각 시나리오는 그대로 복사해 `k6/scripts/` 에 두고 환경에 맞춰 BASE_URL 만 수정해서 사용 가능하다.
공통 헬퍼는 `k6/lib/auth.js`, `k6/lib/env.js` 에 분리.

---

## 0. 공통 헬퍼

### `k6/lib/env.js`
```js
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
```

### `k6/lib/auth.js`
```js
import http from 'k6/http';

export function auth(baseUrl, email = 'loadtest@example.com', password = 'password') {
  const res = http.post(`${baseUrl}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 200) {
    throw new Error(`auth failed: ${res.status} ${res.body}`);
  }
  return res.json('accessToken');
}
```

### `k6/lib/checks.js`
```js
import { check } from 'k6';

export function checkOk(res, name) {
  return check(res, {
    [`${name}: status 2xx`]: (r) => r.status >= 200 && r.status < 300,
    [`${name}: has body`]:   (r) => r.body && r.body.length > 0,
  });
}
```

---

## 1. smoke.js

가장 기본. 매 빌드/PR 에서 5초~1분 안에 끝나야 한다.

```js
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/env.js';

export const options = {
  vus: 1,
  duration: '30s',
  thresholds: {
    http_req_failed:   ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
    checks:            ['rate>0.99'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/actuator/health`);
  check(res, {
    'health 200':            (r) => r.status === 200,
    'status is UP':          (r) => r.json('status') === 'UP',
  });
}
```

---

## 2. load.js

정상 트래픽을 일정 시간 유지. SLA 검증의 본 시나리오.

```js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { auth } from '../lib/auth.js';
import { BASE_URL } from '../lib/env.js';

const createTrend = new Trend('order_create_duration');

export const options = {
  stages: [
    { duration: '30s', target: 20 },   // ramp-up
    { duration: '5m',  target: 20 },   // sustain
    { duration: '30s', target: 0 },    // ramp-down
  ],
  thresholds: {
    http_req_failed:        ['rate<0.01'],
    http_req_duration:      ['p(95)<300'],
    order_create_duration:  ['p(95)<400'],
    checks:                 ['rate>0.99'],
  },
};

export function setup() {
  return { token: auth(BASE_URL) };
}

export default function (data) {
  const headers = {
    Authorization: `Bearer ${data.token}`,
    'Content-Type': 'application/json',
  };

  const create = http.post(`${BASE_URL}/api/v1/orders`,
    JSON.stringify({ productId: 'P1', quantity: 1 }),
    { headers, tags: { endpoint: 'create-order' } });

  createTrend.add(create.timings.duration);
  check(create, {
    'create 201':     (r) => r.status === 201,
    'has orderId':    (r) => r.json('orderId') !== undefined,
  });

  if (create.status === 201) {
    const orderId = create.json('orderId');
    const get = http.get(`${BASE_URL}/api/v1/orders/${orderId}`,
      { headers, tags: { endpoint: 'get-order' } });
    check(get, { 'get 200': (r) => r.status === 200 });
  }

  sleep(1);
}
```

---

## 3. stress.js

정상의 2~5 배까지 ramp 후 안정 유지. 한계점 + degradation 패턴 관찰.

```js
import http from 'k6/http';
import { check } from 'k6';
import { auth } from '../lib/auth.js';
import { BASE_URL } from '../lib/env.js';

export const options = {
  stages: [
    { duration: '1m', target: 50 },
    { duration: '2m', target: 100 },
    { duration: '2m', target: 200 },
    { duration: '5m', target: 200 },
    { duration: '1m', target: 0 },
  ],
  thresholds: {
    http_req_failed:   ['rate<0.05'],     // stress 는 5%까지 허용
    http_req_duration: ['p(95)<800'],     // 정상 대비 완화
  },
};

export function setup() {
  return { token: auth(BASE_URL) };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };
  const res = http.get(`${BASE_URL}/api/v1/orders`, { headers });
  check(res, { 'status 2xx': (r) => r.status >= 200 && r.status < 300 });
}
```

---

## 4. spike.js

이벤트성 폭증 (티켓팅, 선착순) 시나리오. autoscaling / warmup 부재 검출.

```js
import http from 'k6/http';
import { check } from 'k6';
import { auth } from '../lib/auth.js';
import { BASE_URL } from '../lib/env.js';

export const options = {
  stages: [
    { duration: '10s', target: 5 },     // 평소
    { duration: '10s', target: 500 },   // 급증
    { duration: '1m',  target: 500 },   // 유지
    { duration: '10s', target: 5 },     // 회복
  ],
  thresholds: {
    http_req_failed:   ['rate<0.10'],   // spike 는 10%까지 관찰
    http_req_duration: ['p(95)<1500'],
  },
};

export function setup() {
  return { token: auth(BASE_URL) };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}`, 'Content-Type': 'application/json' };
  const res = http.post(`${BASE_URL}/api/v1/coupons/issue`,
    JSON.stringify({ couponCode: 'EVENT-2026' }),
    { headers });

  check(res, {
    'status 200/409':  (r) => [200, 409].includes(r.status),  // 매진은 409 정상
  });
}
```

> **선착순 / 재고 시나리오에서는 409 (sold out) 가 정상 응답**. error 로 잡지 않도록 check 에 포함.

---

## 5. soak.js

장시간(1~4시간) 정상 부하 유지. 메모리 누수 / 커넥션 풀 누수 검출.

```js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { auth } from '../lib/auth.js';
import { BASE_URL } from '../lib/env.js';

export const options = {
  stages: [
    { duration: '5m',  target: 30 },
    { duration: '2h',  target: 30 },     // 장시간 유지
    { duration: '5m',  target: 0 },
  ],
  thresholds: {
    http_req_failed:   ['rate<0.01'],
    http_req_duration: ['p(95)<400'],
  },
};

export function setup() {
  return { token: auth(BASE_URL) };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };
  const res = http.get(`${BASE_URL}/api/v1/orders?page=0&size=20`, { headers });
  check(res, { 'status 200': (r) => r.status === 200 });
  sleep(2);
}
```

> 과제 전형에서는 보통 생략. 운영 서비스에서 weekly / monthly 검증으로 사용.

---

## 시나리오 선택 가이드

| 과제 시간 | 권장 시나리오 |
|---|---|
| 4 시간 | smoke 만 |
| 8 시간 | smoke + load |
| 16 시간+ | smoke + load + stress |
| 운영 | 위 모두 + soak (격주) |
