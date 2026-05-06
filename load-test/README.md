# load-test — Phase 9 E2E 시나리오 (k6)

`docs/prompts/day1-kickoff.md` 의 Phase 9 검증 6 시나리오 + Circuit Breaker 시뮬레이션 1건을
**k6 1.5+** 스크립트로 자동화한 것입니다. 본 디렉토리의 의도는 *부하 테스트가 아니라 기능 회귀
테스트* — VU 1 / iterations 1 로 동작 정확성을 assertion 으로 증명합니다. (Day 4 시점에 본격 부하
시나리오 (`smoke.js` / `load.js` / `spike.js`) 를 같은 디렉토리에 추가합니다.)

---

## 1. 사전 준비

### 1.1 k6 설치

```bash
brew install k6                  # macOS
# 또는 https://grafana.com/docs/k6/latest/set-up/install-k6/

k6 --version                     # v1.x 권장
```

### 1.2 인프라 기동

MySQL + Redis 가 떠 있어야 합니다. (Kafka 는 Day 1 단계에선 미사용)

```bash
docker compose up -d mysql redis
```

### 1.3 깨끗한 상태로 시작

같은 사용자 ID 가 이전 실행에서 남긴 `(user_id, idempotency_key)` 행이 있으면 일부 시나리오가
DB UNIQUE 충돌로 의도와 다르게 동작할 수 있습니다. 매 실행 전 다음 명령을 권장합니다.

```bash
docker exec promotion-mysql mysql -u promotion -ppromotion -e \
    "USE server_a; TRUNCATE TABLE issue_request;"
docker exec promotion-redis redis-cli FLUSHALL
```

---

## 2. 시나리오 1~6 — Stub Profile 로 실행

### 2.1 Server A 기동 (local profile = StubCouponIssuingClient)

```bash
./gradlew :server-a:bootRun --args='--spring.profiles.active=local'
```

기동 확인:

```bash
curl -sS http://localhost:8080/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}
```

### 2.2 시나리오 실행

각 스크립트는 1 VU / 1 iteration / threshold `checks rate==1.0` 으로 실행됩니다. 단일 assertion
이 실패하면 exit code != 0 + thresholds crossed 로 표시됩니다.

```bash
# 개별 실행
k6 run load-test/scenarios/phase9-01-issue-success.js
k6 run load-test/scenarios/phase9-02-idempotency.js
k6 run load-test/scenarios/phase9-03-cross-user.js
k6 run load-test/scenarios/phase9-04-rate-limit.js
k6 run load-test/scenarios/phase9-05-missing-header.js
k6 run load-test/scenarios/phase9-06-validation.js

# 6개 일괄 실행 (07 은 사전 조건이 다르므로 분리)
./load-test/run-phase9-stub.sh
```

기본 BASE_URL 은 `http://localhost:8080`. 다른 호스트는 환경변수로 주입:

```bash
k6 run -e BASE_URL=http://staging.example.com:8080 load-test/scenarios/phase9-01-issue-success.js
```

---

## 3. 시나리오 7 — Circuit Breaker (Default Profile + Server B 부재)

### 3.1 사전 조건

- Server A 를 **`local` profile 없이** 기동 → `RestClientCouponIssuingClient` 활성화
- Server B 는 기동하지 않음 → RestClient 호출이 `Connection refused` 로 실패하도록

```bash
# 시나리오 1~6 으로 이미 기동 중이라면 먼저 stop
kill $(lsof -ti:8080) 2>/dev/null

# DB / Redis 리셋 (이전 시나리오의 흔적 제거)
docker exec promotion-mysql mysql -u promotion -ppromotion -e \
    "USE server_a; TRUNCATE TABLE issue_request;"
docker exec promotion-redis redis-cli FLUSHALL

# Server A 를 기본 profile 로 기동 (RestClient 활성화)
./gradlew :server-a:bootRun
```

### 3.2 시나리오 실행

```bash
k6 run load-test/scenarios/phase9-07-circuit-breaker.js
```

스크립트는 12회 호출 후 `/actuator/circuitbreakers` 를 폴링해 다음을 검증합니다:

- 12회 모두 HTTP 503
- 마지막 응답 `Retry-After: 5` 헤더
- CB state == `OPEN`

### 3.3 자동 회복 (선택)

5초 대기 후 CB 가 `HALF_OPEN` 으로 자동 전환되는지 수동 확인:

```bash
sleep 6 && curl -sS http://localhost:8080/actuator/circuitbreakers
# state: HALF_OPEN
```

---

## 4. 시나리오 ↔ Phase 9 매핑

| 파일 | Phase 9 | 검증 대상 ADR / 모듈 |
|---|---|---|
| `phase9-01-issue-success.js` | 1 정상 발급 | API 진입점 (Controller / Service / Stub) |
| `phase9-02-idempotency.js` | 2 멱등성 | ADR-004 + IdempotencyFilter |
| `phase9-03-cross-user.js` | 3 다른 user 같은 KEY | IdempotencyKey 의 user-scoped 보장 |
| `phase9-04-rate-limit.js` | 4 Rate Limit | ADR-005 + Bucket4j Lettuce + RateLimitFilter |
| `phase9-05-missing-header.js` | 5 헤더 누락 | GlobalExceptionHandler MissingRequestHeaderException |
| `phase9-06-validation.js` | 6 validation 실패 | jakarta.validation + GlobalExceptionHandler |
| `phase9-07-circuit-breaker.js` | + 시뮬레이션 | ADR-001 + Resilience4j CircuitBreaker + RestClient |

---

## 5. 결과 해석

### 5.1 정상 통과

```
✓ checks         : 100.00% (N out of N)
✓ http_req_failed: ...
THRESHOLDS  ✓ checks
```

`checks rate==1.0` threshold 가 통과하면 exit code 0.

### 5.2 실패 사례

```
✗ checks         : 80.00% (4 out of 5)
THRESHOLDS  ✗ checks   ← 실패한 assertion 이 위에 나열됨
```

체크 메시지에 `[NN]` prefix 가 붙어 있으므로 어느 시나리오의 어느 단계가 실패했는지 즉시 파악 가능.

### 5.3 자주 마주치는 실패

| 증상 | 원인 / 회귀 |
|---|---|
| 시나리오 1/2/3 의 200 이 503 | local profile 누락 → Stub 대신 RestClient 활성화 |
| 시나리오 4 의 1~10 호출이 200 미만 | 이전 실행 잔여 트래픽이 Bucket 토큰 소진 → Redis FLUSHALL |
| 시나리오 7 에서 CB state == CLOSED | 호출 수가 `minimum-number-of-calls(10)` 미만 — 본 스크립트는 12회 발생 |
| 시나리오 7 에서 503 대신 200 | Server B 가 실수로 떠 있음 → 종료 후 재실행 |
| 시나리오 5/6 의 응답 코드가 401 | 인증 미적용 / 단순 X-User-Id 헤더 사용 확인 |

---

## 6. CI 통합 (Day 4 이후)

GitHub Actions 워크플로 예시 (Day 4 부하 테스트와 함께 도입 검토):

```yaml
- run: docker compose up -d mysql redis
- run: ./gradlew :server-a:bootRun --args='--spring.profiles.active=local' &
- run: bash load-test/wait-up.sh
- run: ./load-test/run-phase9-stub.sh
```

Day 1 시점에선 로컬 검증만 수행.
