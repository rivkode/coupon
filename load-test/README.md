# load-test — k6 부하 시나리오

신규 설계의 발급 흐름 — 인스턴스당 1000 TPS 의 응답 latency / acceptance rate 를 측정.

---

## 1. 사전 준비

### 1.1 k6 설치

```bash
brew install k6     # macOS
k6 --version        # v1.x
```

### 1.2 인프라 + 세 서비스 기동

```bash
# bootJar 빌드
./gradlew clean :server-a:bootJar :server-b:bootJar :server-c:bootJar -x test

# 인프라 + 3 서비스 (각 서비스 cpus=1, mem_limit=2g)
docker compose up -d --build

# 헬스 확인 (모두 UP 까지 ~30~60초)
docker compose ps
curl -sS http://localhost:8080/actuator/health   # server-a
curl -sS http://localhost:8081/actuator/health   # server-b
curl -sS http://localhost:8082/actuator/health   # server-c
```

### 1.3 마스터 데이터 시드 (Server C) — **자동화됨**

`run-integrated.sh` 가 부하 시작 전에 `load-test/seed.sql` 을 적용해 event / coupon_type / inventory 를 만들고 (idempotent), 매 run 마다 `user_coupon` / `outbox_event` 를 비워 결과 집계를 깔끔하게 합니다. 수동 INSERT 불필요.

> 시드 SQL 자체는 `load-test/seed.sql` 참조. `${TOTAL_INVENTORY}` 는 환경변수로 치환됩니다.

---

## 2. 시나리오 실행

```bash
./load-test/run-integrated.sh
# CLAUDE.md §1 명세 (이벤트당 10,000 장)
TOTAL_INVENTORY=10000 ./load-test/run-integrated.sh
# 매진 없이 TPS 한계만 측정하려면 부하량보다 큰 값
TOTAL_INVENTORY=1000000 ./load-test/run-integrated.sh
```

스크립트 흐름:
1. 헬스체크 (server-a/b/c)
2. Redis FLUSHDB
3. **시드 적용 + 재고 reset + user_coupon/outbox 정리**
4. k6 시나리오 (`issue-1k-tps`) 실행
5. **Kafka pipeline drain 대기** (`DRAIN_WAIT_SECONDS`, 기본 10s)
6. **결과 집계** — 입구 (k6 카운터) vs 출구 (server-c MySQL) 분리 출력

### 2.1 환경변수

| 변수 | 기본값 | 용도 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | server-a endpoint |
| `EVENT_ID` | `1` | 부하 대상 이벤트 |
| `COUPON_TYPE_ID` | `1` | 부하 대상 쿠폰 타입 |
| `TOTAL_INVENTORY` | `10000` | 시드 시 재고 row 의 `total_inventory` / `available_count` |
| `DRAIN_WAIT_SECONDS` | `10` | k6 종료 후 Kafka consume 잔여 처리 대기 |
| `MYSQL_CONTAINER` | `promotion-mysql` | docker exec 대상 |

---

## 3. 시나리오 매핑

| 파일 | 부하 | 의도 |
|---|---|---|
| `issue-1k-tps.js` | constant-arrival-rate 1000 / 1s × 60s | 인스턴스당 1000 TPS 발급 부하. p95 < 200 ms / p99 < 400 ms / 5xx < 0.5% |

ADR-001 의 "B 는 즉시 접수 완료 응답" 모델이라 응답 latency 가 짧다 (Redis HSET + Kafka publish 수 ms).

---

## 4. 결과 해석

### 4.1 입구 카운터 (k6) — Server A 의 응답 기준

| 카운터 | 의미 | 정상 |
|---|---|---|
| `http_reqs` | 총 POST 요청 수 | rate × duration ≈ 60,000 (1000 TPS × 60s) |
| `issue_accepted` | 200 + status=ACCEPTED — B 의 Redis 적재 + Kafka publish 까지 완료 | 대부분 |
| `issue_duplicate` | 200 + status=DUPLICATE | 정상 (1 인 1 장 제약 자연 차단) |
| `issue_internal_error` | 503 (CB OPEN / B 일시 장애) | 작아야 함 |
| `issue_other` | 그 외 | 0 이어야 함 (>0 시 결함 신호) |

### 4.2 출구 — Server C 의 실제 발급 결과 (run-integrated.sh 가 집계)

| 항목 | 의미 |
|---|---|
| `user_coupon SUCCESS` | **사용자가 받은 실제 쿠폰 건수** (= 비관적 락으로 재고 차감 성공) |
| `user_coupon SOLD_OUT` | 재고 소진 후 도착한 메시지 |
| `user_coupon FAILED` | 이벤트 종료 등 비즈니스 거부 |
| `inventory.available_count` | 차감 후 남은 재고. **`total - available == SUCCESS`** 여야 정합 |
| `outbox_event PENDING/PUBLISHED` | poller 가 result topic 으로 publish 완료 여부 |

**`issue_accepted`(입구) 와 `user_coupon TOTAL`(출구) 의 갭** = Kafka consume 잔여. drain 시간을 늘리거나 consumer concurrency 조정 필요.

### 4.3 통과 기준 (thresholds)

### 4.3 통과 기준 (thresholds)

- `http_req_duration p95 < 200ms` — Redis 적재 + Kafka publish 의 합리적 한계
- `http_req_duration p99 < 400ms` — long tail 허용 (cold start GC 등)
- `http_req_failed rate < 0.005` — 0.5% 이하 (Circuit Breaker 일시 OPEN 허용)

### 4.4 관측

부하 중 다음 지표를 함께 모니터링:

- Grafana http://localhost:3000 (admin/admin) — JVM heap / CPU / HTTP p95 / HikariCP / Kafka consumer lag
- Kafka UI http://localhost:8085 — `coupon-issue-request` / `coupon-issue-result` lag

---

## 5. 트러블슈팅

| 증상 | 원인 | 해결 |
|---|---|---|
| 시나리오 첫 ~수초 503 다발 | Cold start, CB sliding window 가 slow call 로 OPEN | warmup 필요 — 시나리오 시작 전 1~2회 단발 호출 |
| 모두 DUPLICATE | 이전 run 의 Redis pending 잔존 | `docker exec promotion-redis redis-cli FLUSHDB` |
| `issue_other > 0` | 4xx — body invalid (마스터 데이터는 자동 시드되므로 보통 다른 원인) | k6 출력 + server-a 로그 확인 |
| `SUCCESS == 0` 인데 `issue_accepted > 0` | C 가 아직 consume 못 했거나 inventory row 부재 | `DRAIN_WAIT_SECONDS` 늘리기 / `seed.sql` 적용 여부 확인 |
| 차감량 != SUCCESS | 비관적 락 정합성 문제 | 처리 코드/락 경로 점검 (ADR-003) |
| C consumer lag 폭증 | 1 vCPU MySQL-C 의 비관적 락 처리량 한계 도달 | `app.kafka.consumer.concurrency`/`max-poll-records` 조정 또는 인스턴스 추가 |
