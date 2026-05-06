# load-test — 통합 e2e 시나리오 (k6)

`server-a` + `server-b` + MySQL + Redis 가 **모두 기동된 단일 환경**에서 동작 정확성을
assertion 으로 검증합니다. 부하 테스트가 아니라 기능 회귀 — VU 1 / iterations 1 시나리오
6개 + 작은 burst 1개. (본격 1 vCPU 부하 / 사이징은 Day 4 의 별도 시나리오 영역.)

---

## 1. 사전 준비

### 1.1 k6 설치

```bash
brew install k6                  # macOS
# 또는 https://grafana.com/docs/k6/latest/set-up/install-k6/

k6 --version                     # v1.x 권장
```

### 1.2 인프라 + 두 서비스 모두 기동

```bash
# 인프라
docker compose up -d mysql redis

# server-b (local profile, port 8081)
./gradlew :server-b:bootRun --args='--spring.profiles.active=local' &

# server-a (default profile, port 8080, RestClient 활성, base-url=8081)
./gradlew :server-a:bootRun &
```

기동 확인:

```bash
curl -sS http://localhost:8080/actuator/health      # server-a UP
curl -sS http://localhost:8081/actuator/health      # server-b UP
```

### 1.3 깨끗한 상태로 시작 (옵션)

이전 실행이 남긴 `(user_id, idempotency_key)` 행 / Redis 캐시가 일부 시나리오의 의도와
부딪칠 수 있습니다. 첫 실행이 아니면 권장:

```bash
docker exec promotion-mysql mysql -u promotion -ppromotion -e \
    "USE server_a; TRUNCATE TABLE issue_request;"
docker exec promotion-redis redis-cli FLUSHALL
```

`run-integrated.sh` 가 매 실행마다 stock seed 와 outbox truncate 는 자동 수행합니다.

---

## 2. 시나리오 일괄 실행

```bash
./load-test/run-integrated.sh
```

스크립트가 수행하는 일:

1. **Redis stock seed** — `event:1:stock:0~9` 각 100 (총 1,000장). `STOCK_PER_SHARD` env 로 변경.
2. **MySQL outbox truncate** — 사후 검증 정확성을 위해 fail-fast.
3. **CB warmup** — 5번 curl 로 server-a 의 Resilience4j sliding-window 정상화 (cold start 시 read-timeout 200ms 초과로 CB OPEN 되는 함정 회피).
4. **시나리오 7개 일괄 실행**:
   - phase9-01 ~ 06 (1 VU / 1 iter, `checks rate==1.0`)
   - day2-04-burst (50 VU × 4 iter, 통합 burst)
5. **Outbox 행 수 사후 출력** — warmup + SUCCEEDED 합과 일치해야 정상.

---

## 3. 시나리오 매핑

| 파일 | 검증 대상 | 통과 조건 |
|---|---|---|
| `phase9-01-issue-success.js` | 정상 발급 (server-a → server-b → ISSUED) | 200 + status=SUCCEEDED + couponCode 12 chars |
| `phase9-02-idempotency.js` | server-a IdempotencyFilter (ADR-004) — 같은 idem 두 번째는 캐시 hit | 두 응답의 requestId / couponCode 동일 |
| `phase9-03-cross-user.js` | (user_id, idempotency_key) UNIQUE — 다른 user 가 같은 idem 으로 충돌 안 함 | 두 user 모두 발급, requestId / couponCode 다름 |
| `phase9-04-rate-limit.js` | Bucket4j Lettuce backend (ADR-005) — 1초 15회 호출 시 11~15번째 429 | 처음 10건 200 + 나머지 429 + Retry-After |
| `phase9-05-missing-header.js` | server-a 입구 검증 — Idempotency-Key 누락 | 400 + code=MISSING_HEADER |
| `phase9-06-validation.js` | jakarta.validation — eventId 누락 | 400 + code=VALIDATION_FAILED + fieldErrors |
| `day2-04-burst.js` | 통합 burst — 50 VU × 4 iter (200 req) → ISSUED + SOLD_OUT 합 == 200 | errors==0, SUCCEEDED ≤ stock |

---

## 4. 환경변수

| 변수 | 기본값 | 용도 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | server-a endpoint |
| `EVENT_ID` | `1` | stock seed 대상 이벤트 |
| `STOCK_PER_SHARD` | `100` | 샤드별 stock (총 STOCK_PER_SHARD × 10) |
| `WARMUP` | `5` | runner 의 CB warmup curl 수 + day2-04 setup() warmup 수 |

예: stock 200 으로 줄여 burst 의 SOLD_OUT 분포를 늘리고 싶다면

```bash
STOCK_PER_SHARD=20 ./load-test/run-integrated.sh   # 총 stock 200
```

---

## 5. 결과 해석

### 5.1 정상 통과

```
✓ phase9-01 ~ phase9-06 모두 checks rate=100%
[d2-04 burst] SUCCEEDED=900~1000 FAILED=200~100 RATE_LIMITED=0 CIRCUIT_BREAKER_503=0 OTHER_ERROR=0
[verify] coupon_issue_outbox rows = (warmup 5 + phase9 발급분 + SUCCEEDED)
All integrated scenarios passed.
```

### 5.2 day2-04 burst 카운터

| 카운터 | HTTP | 의미 | 정상 / 이상 |
|---|---|---|---|
| `SUCCEEDED` | 200 + status SUCCEEDED | server-b Lua 발급 + Outbox INSERT 성공 | **정상** |
| `FAILED` | 200 + status FAILED | SOLD_OUT (자기 샤드 재고 0) | **정상** (재고 소진의 정상 응답) |
| `RATE_LIMITED` | 429 | 사용자당 10 req/sec 초과 | **정상** (의도된 보호) |
| `CIRCUIT_BREAKER_503` | 503 + Retry-After | server-a CB OPEN 또는 server-b 보상 후 | **이상** — 값이 크면 cold start (warmup 부족) |
| `OTHER_ERROR` | 그 외 | 200/429/503 외 응답 | **결함** |

---

## 6. 트러블슈팅

### 6.1 phase9-01 의 status 가 SUCCEEDED 가 아니라 FAILED

**원인**:
- server-a 가 `local` profile 로 떴거나 (RestClient 비활성)
- server-b 미기동
- stock 미seed

**해결**: §1.2 의 부팅 절차 다시 확인. `run-integrated.sh` 가 stock 자동 seed.

### 6.2 시나리오 모두 503 (CB OPEN)

**원인**: cold start 첫 호출이 read-timeout 200ms 를 초과 + CB sliding-window 의 절반 이상이 slow call 로 마킹 → OPEN.

**해결**: `run-integrated.sh` 의 step 3 (warmup curl) 가 자동 회피. server-a 가 방금 부팅됐다면 충분한 시간 (10초+) 후 재실행.

### 6.3 phase9-04 의 1~10 호출 중 일부가 429

**원인**: 이전 실행 잔여 토큰 — Redis 의 사용자별 Bucket 이 과다 소진된 상태.

**해결**: `docker exec promotion-redis redis-cli FLUSHALL` 후 재실행.

### 6.4 outbox 행 수가 예상과 다름

- 작음: 일부 시나리오 실패 (시나리오 출력 확인)
- 큼: 이전 실행 행이 남음 — `run-integrated.sh` 가 자동 truncate, 실패 시 stderr 확인.

---

## 7. 본격 부하 / 사이징 (Day 4 영역)

본 디렉토리는 정합성 회귀가 목적. 실제 1 vCPU / 10,000 TPS 부하 측정 + 인스턴스 사이징은
Day 4 시점의 별도 시나리오 (`smoke.js` / `load.js` / `spike.js`) 에서 다룹니다 —
`capacity-planning` 스킬 + `system-design-reviewer` 가 검증.
