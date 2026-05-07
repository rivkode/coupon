# load-test — 통합 e2e 시나리오 (k6)

`server-a` + `server-b` + `server-c` + MySQL + Redis + Kafka 가 **모두 기동된 단일 환경**에서
동작 정확성을 assertion 으로 검증합니다. 부하 테스트가 아니라 기능 회귀 — VU 1 / iterations 1
시나리오 11개 + 작은 burst 1개. (본격 1 vCPU 부하 / 사이징은 Day 4 의 별도 시나리오 영역.)

> **자원 제약**: 평가 5축 ⑤ 의 전제는 각 서비스 **1 vCPU / 2 GB RAM**. docker compose 모드 (§1.2 권장) 가 컨테이너 단위로 자원 제약을 강제 — 사이징 / 부하 측정 결과의 신뢰성 확보. bootRun 모드는 빠른 개발용.

---

## 1. 사전 준비

### 1.1 k6 설치

```bash
brew install k6                  # macOS
# 또는 https://grafana.com/docs/k6/latest/set-up/install-k6/

k6 --version                     # v1.x 권장
```

### 1.2 인프라 + 세 서비스 모두 기동

#### A. docker compose 모드 (**권장 — 자원 제약 강제**)

각 서비스가 cpus=1.0 + mem_limit=2g 컨테이너로 실행. 사이징 / 부하 검증의 신뢰성 확보.

```bash
# bootJar 빌드 (호스트)
./gradlew clean :server-a:bootJar :server-b:bootJar :server-c:bootJar -x test

# 인프라 + 3 서비스 한 번에
docker compose up -d --build

# 모든 컨테이너 healthy 까지 ~30~60초 대기
docker compose ps
```

#### B. bootRun 모드 (개발 편의 — 자원 제약 없음)

코드 변경 후 빠른 검증용. 호스트 자원을 무제한 사용.

```bash
docker compose up -d mysql redis kafka                # 인프라만
./gradlew :server-b:bootRun --args='--spring.profiles.active=local' &
./gradlew :server-a:bootRun &
./gradlew :server-c:bootRun &
```

#### 헬스 확인 (양쪽 모드 공통)

```bash
curl -sS http://localhost:8080/actuator/health      # server-a UP
curl -sS http://localhost:8081/actuator/health      # server-b UP
curl -sS http://localhost:8082/actuator/health      # server-c UP
```

### 1.3 깨끗한 상태로 시작 (옵션)

이전 실행이 남긴 `(user_id, idempotency_key)` 행 / Redis 캐시가 일부 시나리오의 의도와
부딪칠 수 있습니다. 첫 실행이 아니면 권장:

```bash
docker exec promotion-mysql mysql -upromotion -ppromotion -e \
    "USE server_a; TRUNCATE TABLE issue_request; \
     USE server_b; TRUNCATE TABLE coupon_issue_outbox; \
     USE server_c; TRUNCATE TABLE coupon;"
docker exec promotion-redis redis-cli FLUSHALL
```

`run-integrated.sh` 가 매 실행마다 stock seed + 세 schema 의 truncate 를 자동 수행합니다.

---

## 2. 시나리오 일괄 실행

```bash
./load-test/run-integrated.sh
```

스크립트가 수행하는 일:

1. **Redis FLUSHDB** + **세 schema TRUNCATE** — server_a/server_b/server_c 모두 비움.
2. **Stock seed** — `event:1:stock:0~9` 각 100 (총 1,000장). `STOCK_PER_SHARD` env 로 변경.
3. **헬스체크** — server-a / server-b / server-c 모두 UP 확인. 부팅 불완전 시 fail-fast.
4. **CB warmup** — 5번 curl 로 server-a 의 Resilience4j sliding-window 정상화 (cold start 시 read-timeout 200ms 초과로 CB OPEN 되는 함정 회피).
5. **시나리오 12개 일괄 실행**:
   - phase9-01 ~ 06 (server-a 발급 영역)
   - day2-04-burst (50 VU × 4 iter, 통합 burst)
   - day3-01 ~ 05 (server-c redeem 영역)
6. **사후 검증** — `coupon_issue_outbox` 행 수 + `server_c.coupon` 행 수 + `used_at NOT NULL` 행 수 출력.

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
| `day3-01-redeem-success.js` | A→B→C e2e — 발급 → Kafka consume → redeem 정상 (ADR-001/002) | redeem 200 + newlyRedeemed=true |
| `day3-02-redeem-idempotent.js` | redeem 도메인 자체 멱등 (ADR-007) — 다른 idem 으로 재호출도 200 | newlyRedeemed=true→false + redeemedAt 동일 |
| `day3-03-redeem-ownership-mask.js` | 다른 user 시도 → 404 마스킹 (보안), 사이드 이펙트 없음 | otherId 404 NOT_FOUND + owner 후속 멱등 |
| `day3-04-redeem-not-found.js` | 존재하지 않는 코드 → 마스킹 일관성 | 404 NOT_FOUND + 메시지에 code 누설 안 함 |
| `day3-05-redeem-missing-header.js` | server-c GlobalExceptionHandler — X-User-Id 누락 | 400 + code=MISSING_HEADER |

---

## 4. 환경변수

| 변수 | 기본값 | 용도 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | server-a endpoint |
| `SERVER_B_BASE_URL` | `http://localhost:8081` | server-b endpoint (헬스체크용) |
| `SERVER_C_BASE_URL` | `http://localhost:8082` | server-c endpoint (redeem) |
| `EVENT_ID` | `1` | stock seed 대상 이벤트 |
| `STOCK_PER_SHARD` | `100` | 샤드별 stock (총 STOCK_PER_SHARD × 10) |
| `WARMUP` | `5` | runner 의 CB warmup curl 수 + day2-04 setup() warmup 수 |
| `REDEEM_RETRY_COUNT` | `20` | day3 시나리오의 발급→consume lag 흡수 retry 횟수 (총 윈도우 10초) |
| `REDEEM_RETRY_INTERVAL_SEC` | `0.5` | retry 간격 (초). server-b poller 200ms + Kafka 발행 + server-c consume + JPA save 누적 흡수. |

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
✓ day3-01 ~ day3-05 모두 checks rate=100%
[verify] coupon_issue_outbox rows = (warmup 5 + phase9 발급분 + day2 SUCCEEDED + day3-01..03 발급분)
[verify] server_c.coupon rows     = ≈ outbox rows  (used_at NOT NULL: day3 의 redeem 호출 수)
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

### 5.3 day3 시나리오의 lag 흡수

day3-01/02/03 은 발급 직후 redeem 을 시도하므로 **server-b → Kafka → server-c 의 consume lag** 을
흡수해야 한다. 시나리오 안의 `redeemWithRetry()` 가 첫 응답이 404 면 `REDEEM_RETRY_INTERVAL_SEC`
(default 0.5초) 간격으로 `REDEEM_RETRY_COUNT` (default 20회) 까지 재시도 — 총 10초 윈도우.
day2-04 burst 직후 outbox 에 ~100건 누적 + server-c consumer (max-poll-records=10, concurrency=1)
가 따라잡는 시간을 흡수.

`server-c.coupon rows` 가 시나리오 종료 후에도 작으면 (consume lag 이 큼) Kafka broker /
server-c consumer 상태를 확인.

### 5.4 redeem 응답의 timestamp 정밀도 hazard

`Coupon.redeem(Instant.now())` 의 `now` 는 in-memory **microsecond** 정밀도 (`.123456Z`).
이 값이 DB DATETIME(3) 에 저장되면서 **millisecond 로 rounding** (`.123456Z` → `.123Z` 또는
`.576936Z` → `.577Z`). 그래서:

- **첫 redeem 응답** (`newlyRedeemed=true`) 의 `redeemedAt` = in-memory micros (`@Transactional` 안의
  `coupon.getUsedAt()` 그대로).
- **idem 분기 응답** (`newlyRedeemed=false`) 의 `redeemedAt` = DB 에서 읽은 ms (rounding 적용됨).

두 응답을 직접 비교하면 boundary case (`.999600Z` ≠ `1.000Z`) 에서 깨진다. day3-02 / day3-03 은
**두 idem 응답** (둘 다 DB ms 정밀도) 을 비교해 정확히 일치 검증. 향후 `issuedAt` 등 다른 시간
필드를 검증하는 시나리오를 추가할 때 같은 함정에 주의.

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

**해결**: `run-integrated.sh` 의 step 5 (warmup curl) 가 자동 회피. server-a 가 방금 부팅됐다면 충분한 시간 (10초+) 후 재실행.

### 6.3 phase9-04 의 1~10 호출 중 일부가 429

**원인**: 이전 실행 잔여 토큰 — Redis 의 사용자별 Bucket 이 과다 소진된 상태.

**해결**: `docker exec promotion-redis redis-cli FLUSHALL` 후 재실행.

### 6.4 outbox 행 수가 예상과 다름

- 작음: 일부 시나리오 실패 (시나리오 출력 확인)
- 큼: 이전 실행 행이 남음 — `run-integrated.sh` 가 자동 truncate, 실패 시 stderr 확인.

### 6.5 day3-01 의 redeem 이 매번 404 (retry 5회 모두 실패)

**원인**: server-b → Kafka → server-c 흐름 어딘가의 단절.
- Kafka 컨테이너 미기동 — `docker compose up -d kafka`
- server-b poller 가 안 돌고 있음 — `app.outbox.poller.fixed-delay-ms` (default 200) 확인
- server-c consumer 미부팅 — `curl http://localhost:8082/actuator/health`
- topic 자동 생성 실패 — kafka-ui (`http://localhost:8085`) 에서 `coupon.issued` 토픽 존재 확인

**해결**: 위 4가지를 순서대로 점검. server-c 부팅 직후 Kafka coordinator rebalance 가 ~수초 걸리니
부팅 직후 즉시 실행은 피한다. 평소보다 lag 가 큰 환경이면 `REDEEM_RETRY_COUNT=40` 처럼 윈도우 확장.

### 6.6 day3-03 의 owner second redeem 의 redeemedAt 이 다름

**원인**: 다른 user (otherId) 의 redeem 시도가 사이드 이펙트를 만들었다 — 보안 결함 또는 service 의
검증 순서 회귀.

**해결**: `RedeemCouponService` 의 분기 순서 점검 (소유권 → 멱등 → 정상). 단위 테스트
`RedeemCouponServiceTest.redeem_masks_ownership_mismatch` 가 회귀를 막아야 함.

---

## 7. 본격 부하 / 사이징 (Day 4 영역)

본 디렉토리는 정합성 회귀가 목적. 실제 1 vCPU / 10,000 TPS 부하 측정 + 인스턴스 사이징은
Day 4 시점의 별도 시나리오 (`smoke.js` / `load.js` / `spike.js`) 에서 다룹니다 —
`capacity-planning` 스킬 + `system-design-reviewer` 가 검증.

부하 시 관측: `http://localhost:3000` (Grafana, admin/admin) 의 **Promotion Overview** 대시보드 5 패널
(JVM heap / CPU / HTTP p95 / HikariCP active / Tomcat busy) 가 server-a/b/c 오버레이로 표시됩니다.
시나리오 종료 후 retention 2h 동안 시계열 보존 — `docs/reports/01.load-test-results.md` 작성 시 캡처.
