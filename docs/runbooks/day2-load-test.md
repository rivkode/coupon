# 통합 e2e — k6 부하 테스트 로컬 실행 가이드

> 본 문서는 `server-a + server-b + mysql + redis` 가 모두 기동된 **단일 환경**에서
> k6 시나리오 7개 (phase9-01..06 + day2-04 burst) 를 재현하기 위한 절차를 담는다.
> 시나리오 자체의 의도는 코드 주석에, 본 문서는 "어떻게 돌리는가 + 결과를 어떻게 읽는가"
> 만 다룬다. README 통합 시 "검증" 섹션과 합쳐서 정리할 임시 runbook.

---

## 1. 사전 조건

| 항목 | 버전 / 비고 |
|---|---|
| Java | 21 (`./gradlew --version` 으로 확인) |
| Docker | mysql 8.0 + redis 7-alpine 컨테이너 부팅 가능 |
| k6 | `brew install k6` (macOS) / [공식 설치 문서](https://k6.io/docs/get-started/installation/) |
| 포트 | `3306` (mysql), `6379` (redis), `8080` (server-a), `8081` (server-b) 모두 free |

```bash
java -version            # 21
docker version           # daemon up
k6 version               # v0.55+ 권장
lsof -ti:8080 -ti:8081   # 비어있어야 함
```

---

## 2. 부팅 (한 번에 모두)

```bash
# 인프라
docker compose up -d mysql redis

# server-b (local profile, port 8081)
./gradlew :server-b:bootRun --args='--spring.profiles.active=local' &

# server-a (default profile, port 8080, RestClient 활성, base-url=8081)
./gradlew :server-a:bootRun &
```

확인:

```bash
curl -sS http://localhost:8080/actuator/health     # server-a UP
curl -sS http://localhost:8081/actuator/health     # server-b UP
```

> **중요**: server-a 는 `local` profile **없이** 기동. local 이면 `StubCouponIssuingClient`
> 가 활성화되어 server-b 호출이 일어나지 않고, 통합 시나리오의 의도가 깨진다.

---

## 3. 시나리오 일괄 실행

```bash
./load-test/run-integrated.sh
```

스크립트가 수행하는 일:

1. **Redis stock seed** — `event:1:stock:0~9` 각 100 (총 1,000장)
2. **MySQL outbox truncate** — 사후 검증 정확성을 위해 fail-fast
3. **CB warmup** — 5번 curl 로 server-a Resilience4j sliding-window 정상화
4. **시나리오 7개 일괄 실행** (phase9-01..06 + day2-04-burst)
5. **Outbox 행 수 사후 출력**

---

## 4. 시나리오별 의미 + 통합 환경 동작

| 시나리오 | 검증 대상 | 통합 환경 동작 |
|---|---|---|
| `phase9-01-issue-success` | 정상 발급 — server-a → server-b → ISSUED | 200 + status SUCCEEDED + couponCode 12 chars |
| `phase9-02-idempotency` | server-a IdempotencyFilter (ADR-004) | 두 응답의 requestId / couponCode 동일 (캐시 hit) |
| `phase9-03-cross-user` | (user_id, idempotency_key) UNIQUE | 두 user 모두 발급, requestId / couponCode 다름 |
| `phase9-04-rate-limit` | Bucket4j Lettuce (ADR-005) | 1초 15회 → 처음 10건 200 + 나머지 429 |
| `phase9-05-missing-header` | server-a 입구 검증 | 400 + code MISSING_HEADER |
| `phase9-06-validation` | jakarta.validation | 400 + code VALIDATION_FAILED + fieldErrors |
| `day2-04-burst` | 통합 burst — 50 VU × 4 iter (200 req) | SUCCEEDED + FAILED 합 == 200, errors 0 |

---

## 5. 기대 출력 (요약)

```
==========================================
  Integrated test setup
  base-url=http://localhost:8080 event=1 stock=1000 warmup=5
==========================================
Warming up server-a Circuit Breaker...
  warmup 1: 200
  warmup 2: 200
  warmup 3: 200
  warmup 4: 200
  warmup 5: 200
==========================================
  Running: load-test/scenarios/phase9-01-issue-success.js
==========================================
✓ checks 100%
==========================================
  Running: load-test/scenarios/phase9-02-idempotency.js
==========================================
✓ checks 100%
... (phase9-03..06 동일)
==========================================
  Running: load-test/scenarios/day2-04-burst.js
==========================================
[d2-04 burst] SUCCEEDED=180 FAILED=20 RATE_LIMITED=0 CIRCUIT_BREAKER_503=0 OTHER_ERROR=0 TOTAL=200 (stock=1000 warmup=5 ceiling=995)
[verify] coupon_issue_outbox rows = (warmup 5 + phase9 발급분 + day2-04 SUCCEEDED)
All integrated scenarios passed.
```

---

## 6. day2-04 burst 카운터 해석

| 카운터 | HTTP | 의미 | 정상 / 이상 |
|---|---|---|---|
| `SUCCEEDED` | 200 + status SUCCEEDED | server-b Lua 발급 + Outbox INSERT 성공 | **정상** |
| `FAILED` | 200 + status FAILED | SOLD_OUT — 자기 샤드 재고 0 | **정상** (재고 소진의 정상 응답) |
| `RATE_LIMITED` | 429 | 사용자당 10 req/sec 초과 | **정상** (의도된 보호) |
| `CIRCUIT_BREAKER_503` | 503 + Retry-After | server-a CB OPEN 또는 server-b 보상 후 | **이상** — 값이 크면 cold start (warmup 부족) |
| `OTHER_ERROR` | 그 외 | 200/429/503 외 응답 | **결함** |

`day2-04-burst.js` 의 thresholds:
- `day2_burst_other_error count==0` — 시스템 결함 없음
- `day2_burst_succeeded count<=(STOCK - WARMUP)` — 재고 권위 (Lua 음수 차단)

---

## 7. 환경변수로 부하 / stock 조정

```bash
# stock 200 으로 줄여 burst 의 SOLD_OUT 분포 늘리기
STOCK_PER_SHARD=20 ./load-test/run-integrated.sh

# 다른 호스트 / 이벤트
BASE_URL=http://staging:8080 EVENT_ID=42 ./load-test/run-integrated.sh
```

`STOCK_PER_SHARD` 가 변경되면 `STOCK / WARMUP` 환경변수가 자동으로 day2-04 의 threshold
계산에 전달된다.

---

## 8. 트러블슈팅

### 8.1 phase9-01 의 status 가 FAILED

**원인 후보**:
- server-a 가 `local` profile 로 떴음 (Stub 활성)
- server-b 미기동
- stock 미seed (`run-integrated.sh` 가 자동 seed 하므로 직접 k6 실행 시 발생)

**확인**:

```bash
ps aux | grep ServerAApplication      # --args 에 'local' 이 없어야
curl -s http://localhost:8081/actuator/health    # server-b UP
docker exec promotion-redis redis-cli MGET \
    event:1:stock:0 event:1:stock:5 event:1:stock:9   # 100, 100, 100
```

### 8.2 시나리오 모두 503 (CB OPEN)

**원인**: cold start 첫 호출이 200ms read-timeout 을 초과 + CB sliding-window 의 절반 이상이 slow call → OPEN.

**해결**: `run-integrated.sh` 의 step 3 (warmup curl 5번) 가 자동 회피. server-a 가 방금 부팅됐다면 충분한 시간 (10초+) 후 재실행하거나, 부팅 후 직접 5번 warmup curl:

```bash
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
# "CLOSED" 가 떠야 시나리오 시작 가능
```

### 8.3 server_b schema 가 없음

**증상**: server-b 부팅 시 Flyway / JPA validate 오류.

**원인**: docker compose 의 mysql init.sql 은 컨테이너 첫 부팅 시에만 실행. 이후 init.sql 변경은 반영 안 됨.

**해결**:

```bash
docker exec promotion-mysql mysql -uroot -prootpassword \
    -e "CREATE DATABASE IF NOT EXISTS server_b CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        GRANT ALL PRIVILEGES ON server_b.* TO 'promotion'@'%';
        FLUSH PRIVILEGES;"
```

### 8.4 phase9-04 의 1~10 호출 중 일부가 429

**원인**: 이전 실행 잔여 토큰 — Redis 의 사용자별 Bucket 이 과다 소진.

**해결**:

```bash
docker exec promotion-redis redis-cli FLUSHALL
# 그 후 ./load-test/run-integrated.sh 가 stock 다시 seed
```

### 8.5 Kafka UI 와 server-b 포트 충돌

**증상**: server-b 부팅 시 `Address already in use 8081`.

**원인**: 이전 docker compose 정의 (Kafka UI 8081) 캐시. PR #8 에서 8085 로 이전됨.

**해결**:

```bash
docker compose down kafka-ui
docker compose up -d --force-recreate kafka-ui    # 8085 재바인딩
```

---

## 9. 본격 부하 / 사이징

본 시나리오는 **정합성 회귀** 가 목적. 실제 1 vCPU / 10,000 TPS 부하 측정 + 인스턴스
사이징 산출은 Day 4 시점의 별도 시나리오 (`smoke.js` / `load.js` / `spike.js`) 에서
다룬다 — `capacity-planning` 스킬 + `system-design-reviewer` 가 검증.

---

## 10. 관련 코드 / 문서

- 시나리오 — `load-test/scenarios/phase9-01..06`, `load-test/scenarios/day2-04-burst.js`
- 공통 헬퍼 — `load-test/lib/{env,payload}.js`
- 통합 runner — `load-test/run-integrated.sh`
- 발급 API — `server-a/.../api/IssueRequestController.java`,
  `server-b/.../api/internal/CouponIssueController.java`
- ADR-001 (A→B sync, 200ms timeout), ADR-003 (Lua), ADR-004 (Idempotency-Key) — `CLAUDE.md` §6
- Outbox 결정 근거 — `docs/decisions/outbox-mysql-vs-redis-streams.md`
