# Day 2 — k6 부하 테스트 로컬 실행 가이드

> 본 문서는 Day 2 의 4 시나리오 (Stub 3 + 통합 1) 를 본인 노트북 / 평가자 환경에서
> 직접 재현하기 위한 절차를 담는다. 시나리오 자체의 의도는 코드 주석에, 본 문서는
> "어떻게 돌리는가 + 결과를 어떻게 읽는가" 만 다룬다.

---

## 1. 사전 조건

| 항목 | 버전 / 비고 |
|---|---|
| Java | 21 (`./gradlew --version` 으로 확인) |
| Docker | mysql 8.0 + redis 7-alpine 컨테이너 부팅 가능 |
| k6 | `brew install k6` (macOS) / [공식 설치 문서](https://k6.io/docs/get-started/installation/) |
| 포트 | `3306` (mysql), `6379` (redis), `8080` (server-a), `8081` (server-b) 모두 free |

```bash
# 사전 점검
java -version            # 21
docker version           # daemon up
k6 version               # v0.55+ 권장
lsof -ti:8080 -ti:8081   # 비어있어야 함
```

---

## 2. 인프라 부팅 (공통)

```bash
docker compose up -d mysql redis
# 필요 시: docker compose up -d  (kafka / kafka-ui 까지 — Day 2 시점엔 미사용)
```

확인:

```bash
docker exec promotion-mysql mysqladmin -uroot -prootpassword ping
docker exec promotion-redis redis-cli ping     # PONG
```

---

## 3. Stub 모드 — 시나리오 1, 2, 3 (server-a 단독)

server-b 부재 상태에서 server-a 의 외부 흐름을 검증. `StubCouponIssuingClient`
(`@Profile("local")`) 가 `CouponIssuingClient` 빈을 대신 주입.

### 3.1 server-a 부팅

```bash
./gradlew :server-a:bootRun --args='--spring.profiles.active=local' &
# 부팅 완료 대기 — "Started ServerAApplication" 로그 또는 actuator/health
curl -s http://localhost:8080/actuator/health       # {"status":"UP",...}
```

### 3.2 시나리오 일괄 실행

```bash
./load-test/run-day2-stub.sh
```

스크립트가 day2-01 / day2-02 / day2-03 을 순서대로 실행. 모두 `checks rate==1.0`
threshold 라 하나라도 실패하면 exit code ≠ 0.

### 3.3 기대 출력 (요약)

```
==========================================
  Running: load-test/scenarios/day2-01-issue-success.js
==========================================
✓ [d2-01] HTTP 200
✓ [d2-01] body.success == true
✓ [d2-01] data.status == SUCCEEDED
✓ [d2-01] couponCode 12 chars
✓ [d2-01] requestId 가 UUID

==========================================
  Running: load-test/scenarios/day2-02-idempotency.js
==========================================
✓ [d2-02] 1st HTTP 200
✓ [d2-02] 1st status SUCCEEDED
✓ [d2-02] 1st couponCode 12 chars
✓ [d2-02] 2nd HTTP 200
✓ [d2-02] 2nd status SUCCEEDED
✓ [d2-02] 2nd couponCode == 1st (멱등)
✓ [d2-02] 2nd requestId == 1st

==========================================
  Running: load-test/scenarios/day2-03-sold-out.js
==========================================
✓ [d2-03] HTTP 200
✓ [d2-03] body.success == true
✓ [d2-03] data.status == FAILED
✓ [d2-03] data.couponCode 미발급(null/없음)
✓ [d2-03] failureReason 포함 stock

All Day 2 stub scenarios passed.
```

### 3.4 시나리오별 의미

| 시나리오 | 무엇을 검증하는가 |
|---|---|
| `day2-01-issue-success` | 정상 발급 흐름 — 200 + status `SUCCEEDED` + 12자 쿠폰 코드 |
| `day2-02-idempotency` | server-a `IdempotencyFilter` (Redis SETNX, ADR-004) 가 같은 키 두 번째 호출을 캐시 hit 으로 차단 — 두 번째 응답이 첫 번째와 byte-for-byte 동일 |
| `day2-03-sold-out` | `StubCouponIssuingClient` 의 `sold-out-*` trigger key 로 SOLD_OUT 응답 강제. 200 + status `FAILED` + `failureReason` 에 "stock" |

---

## 4. 통합 모드 — 시나리오 4 (server-a + server-b)

server-a 가 default profile (RestClient 활성, base-url=8081) 로 부팅 + server-b 가
실제 발급을 처리하는 e2e 검증.

### 4.1 부팅 순서

```bash
# 직전 server-a (local profile) 가 떠 있다면 종료
kill $(lsof -ti:8080) 2>/dev/null

# server-b 먼저
./gradlew :server-b:bootRun --args='--spring.profiles.active=local' &

# server-a — default profile (RestClient + Resilience4j 활성)
./gradlew :server-a:bootRun &

# 둘 다 헬스체크
curl -s http://localhost:8080/actuator/health     # server-a UP
curl -s http://localhost:8081/actuator/health     # server-b UP
```

### 4.2 burst 자동 실행

`run-day2-integrated.sh` 가 사전/사후 작업까지 자동화한다:

```bash
./load-test/run-day2-integrated.sh
```

스크립트가 수행하는 일:

1. **Redis stock seed** — `event:1:stock:0` ~ `event:1:stock:9` 각 100 (총 1,000장).
   `STOCK_PER_SHARD` 환경변수로 변경 가능.
2. **MySQL outbox truncate** — 이전 run 의 잔여 행 제거. 실패 시 `exit 1`
   (사후 검증이 무의미해지므로).
3. **k6 burst 실행** — 50 VU × 4 iter (200 req). `STOCK` / `WARMUP` 환경변수를
   k6 시나리오에 전달하여 threshold (`SUCCEEDED ≤ STOCK - WARMUP`) 자동 동기화.
4. **사후 검증** — `coupon_issue_outbox` 행 수 출력. 정상이면 `WARMUP + SUCCEEDED` 와
   일치.

### 4.3 기대 출력

```
==========================================
  Seeding stock: event=1 10 shards × 10
==========================================
==========================================
  Running: load-test/scenarios/day2-04-burst.js (stock=100 warmup=5)
==========================================

[d2-04 burst] SUCCEEDED=95 FAILED=105 RATE_LIMITED=0 CIRCUIT_BREAKER_503=0 OTHER_ERROR=0 TOTAL=200 (stock=100 warmup=5 ceiling=95)

[verify] coupon_issue_outbox rows = 100  (warmup 5 + SUCCEEDED 95)
Day 2 burst scenario passed.
```

### 4.4 검증 invariant

| 항목 | 기대 | 의미 |
|---|---|---|
| `SUCCEEDED ≤ STOCK - WARMUP` | 95 ≤ 95 | Lua atomic 차감의 **재고 권위** — Lua 가 음수 방지. 초과하면 시스템 결함 |
| `SUCCEEDED + FAILED + RATE_LIMITED + CB_503 == TOTAL` | 200 | 모든 요청이 분류된 응답 → 누락/중복 없음 |
| `coupon_issue_outbox rows == WARMUP + SUCCEEDED` | 100 | Outbox 적재 정확성 — 보상 발동 없는 정상 흐름 |
| `OTHER_ERROR == 0` | 0 | 200/429/503 외의 응답 없음 (validation 실패 등) |

---

## 5. 결과 카운터 해석

`day2-04-burst.js` 의 5 카운터:

| 카운터 | HTTP | 의미 | 정상 / 이상 |
|---|---|---|---|
| `SUCCEEDED` | 200 + status `SUCCEEDED` | server-b Lua 발급 성공 + Outbox INSERT 성공 | **정상** |
| `FAILED` | 200 + status `FAILED` | SOLD_OUT — 자기 샤드 재고 0 | **정상** (재고 소진의 정상 응답) |
| `RATE_LIMITED` | 429 | 사용자당 10 req/sec 초과 (ADR-005) | **정상** (의도된 보호) |
| `CIRCUIT_BREAKER_503` | 503 + Retry-After | server-a CB OPEN (200ms timeout 초과 누적) 또는 server-b 보상 후 | **이상** (값이 크면 cold start / 부하 과다) |
| `OTHER_ERROR` | 그 외 | 200/429/503 외의 응답 | **결함** (validation / 5xx 예외 / 네트워크) |

`CIRCUIT_BREAKER_503` 이 0 이 아니면 §6 트러블슈팅의 cold start 항목 참조.

---

## 6. 트러블슈팅

### 6.1 Stub 모드인데 응답이 잘못 나옴

**증상**: day2-01 이 `data.status: FAILED` 또는 503.

**원인 후보**:
- `--spring.profiles.active=local` 누락 → `RestClientCouponIssuingClient` 가 활성화되어 8081 호출 시도 (server-b 부재 시 connection refused → CB OPEN)
- 부팅 직후 첫 호출이 아직 처리 안 됨

**해결**:

```bash
ps aux | grep ServerAApplication       # --args='--spring.profiles.active=local' 포함 확인
curl -s http://localhost:8080/actuator/info | jq '.activeProfiles'  # 또는 env
```

### 6.2 통합 모드 burst 가 모두 503 (CB OPEN)

**증상**: `CIRCUIT_BREAKER_503=200` (전체).

**원인**: cold start 첫 호출이 200ms read-timeout 을 초과하여 CB sliding-window
의 절반 이상이 slow call → OPEN. 5초 wait 후 HALF_OPEN 에서 또 timeout → 다시 OPEN.

**해결**: server-a 재부팅 후 직접 5번 warmup curl 로 JIT + connection pool 워밍업.

```bash
kill $(lsof -ti:8080) 2>/dev/null
./gradlew :server-a:bootRun &
# 부팅 완료 대기
for i in 1 2 3 4 5; do
    KEY=$(uuidgen)
    curl -s -o /dev/null -w "$i: %{http_code} %{time_total}s\n" \
        -X POST http://localhost:8080/api/v1/coupons/issue-requests \
        -H "X-User-Id: $((60100+i))" \
        -H "Idempotency-Key: $KEY" \
        -H "Content-Type: application/json" \
        -d '{"eventId":1,"deviceId":"t","channel":"WEB","requestedAt":"2026-05-07T00:00:00Z","clientVersion":"1","region":"KR","language":"ko","marketingConsent":true}'
    sleep 0.2
done
curl -s http://localhost:8080/actuator/circuitbreakers | jq '.circuitBreakers.couponIssuing.state'
# "CLOSED" 가 떠야 burst 시작 가능
./load-test/run-day2-integrated.sh
```

`run-day2-integrated.sh` 자체에도 k6 `setup()` 으로 5번 warmup 이 있지만 server-a
JVM 이 정말 새로 부팅된 직후라면 5번도 부족할 수 있다.

### 6.3 server_b schema 가 없음

**증상**: server-b 부팅 시 Flyway / JPA validate 오류.

**원인**: docker compose 의 mysql init.sql 은 컨테이너 첫 부팅 시에만 실행됨.
이후 init.sql 변경은 반영 안 됨.

**해결**:

```bash
docker exec promotion-mysql mysql -uroot -prootpassword \
    -e "CREATE DATABASE IF NOT EXISTS server_b CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        GRANT ALL PRIVILEGES ON server_b.* TO 'promotion'@'%';
        FLUSH PRIVILEGES;"
```

또는 완전 재구축 (volume 삭제 — 다른 schema 데이터도 날아감):

```bash
docker compose down -v && docker compose up -d mysql redis
```

### 6.4 Stock 이 비어있음

**증상**: 통합 burst 가 `SUCCEEDED=0 FAILED=200`.

**원인**: `event:1:stock:*` 키 미설정. Lua 의 `DECR` 가 0 → -1 → INCR 롤백 →
SOLD_OUT.

**해결**: `run-day2-integrated.sh` 가 자동 seed 하지만 수동 확인:

```bash
docker exec promotion-redis redis-cli MGET \
    event:1:stock:0 event:1:stock:1 event:1:stock:2 event:1:stock:3 event:1:stock:4 \
    event:1:stock:5 event:1:stock:6 event:1:stock:7 event:1:stock:8 event:1:stock:9
# 모두 100 (또는 STOCK_PER_SHARD 값) 이어야
```

### 6.5 Kafka UI 와 server-b 포트 충돌

**증상**: server-b 부팅 시 `Address already in use 8081`.

**원인**: `docker compose up -d` 가 kafka-ui 까지 부팅 — 호스트 포트 8085 (PR #8 에서
8081 → 8085 로 이전됨) 인데 이전 docker compose 정의가 8081 인 캐시일 수 있음.

**해결**:

```bash
docker compose down kafka-ui
docker compose up -d --force-recreate kafka-ui    # 8085 재바인딩
```

---

## 7. 다른 부하 / 큰 재고로 실험

```bash
# 재고를 1만 장으로 (각 샤드 1000) + warmup 10
STOCK_PER_SHARD=1000 WARMUP=10 ./load-test/run-day2-integrated.sh
```

본격 1 vCPU 부하 한계는 Day 4 의 capacity-planning 에서 별도로 측정한다 — 본
시나리오는 정합성 검증이 목적이므로 작은 규모를 default 로 둔다.

---

## 8. 관련 코드 / 문서

- 시나리오 — `load-test/scenarios/day2-*.js`
- 공통 헬퍼 — `load-test/lib/{env,payload}.js`
- runner — `load-test/run-day2-stub.sh`, `load-test/run-day2-integrated.sh`
- Stub trigger — `server-a/.../infrastructure/client/StubCouponIssuingClient.java`
- 발급 API — `server-a/.../api/IssueRequestController.java`,
  `server-b/.../api/internal/CouponIssueController.java`
- ADR-001 (A→B sync, 200ms timeout), ADR-003 (Lua), ADR-004 (Idempotency-Key) — `CLAUDE.md` §6
- Outbox 결정 근거 — `docs/decisions/outbox-mysql-vs-redis-streams.md`
