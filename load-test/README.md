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

### 1.3 마스터 데이터 시드 (Server C)

신규 설계는 재고가 C 의 `coupon_type_inventory` 에 있음. 부하 시작 전 event / coupon_type / inventory 가 시드되어 있어야 합니다.

```sql
-- 단일 이벤트 + 단일 coupon_type + 재고 10000 시드 예시
USE server_c;
INSERT INTO event (event_id, name, content, started_at, ended_at)
    VALUES (1, 'concert-presale', 'demo', NOW(3) - INTERVAL 1 HOUR, NOW(3) + INTERVAL 1 HOUR);
INSERT INTO coupon_type (coupon_type_id, event_id, name, discount_rate)
    VALUES (1, 1, '10% discount', 10);
INSERT INTO coupon_type_inventory (event_id, coupon_type_id, total_inventory, available_count)
    VALUES (1, 1, 10000, 10000);
```

---

## 2. 시나리오 실행

```bash
./load-test/run-integrated.sh
```

스크립트는 헬스체크 → Redis FLUSHDB → k6 시나리오 (`issue-1k-tps`) 를 실행합니다.

### 2.1 환경변수

| 변수 | 기본값 | 용도 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | server-a endpoint |
| `EVENT_ID` | `1` | 부하 대상 이벤트 |
| `COUPON_TYPE_ID` | `1` | 부하 대상 쿠폰 타입 |

---

## 3. 시나리오 매핑

| 파일 | 부하 | 의도 |
|---|---|---|
| `issue-1k-tps.js` | constant-arrival-rate 1000 / 1s × 60s | 인스턴스당 1000 TPS 발급 부하. p95 < 200 ms / p99 < 400 ms / 5xx < 0.5% |

ADR-001 의 "B 는 즉시 접수 완료 응답" 모델이라 응답 latency 가 짧다 (Redis HSET + Kafka publish 수 ms).

---

## 4. 결과 해석

### 4.1 카운터

| 카운터 | 의미 | 정상 |
|---|---|---|
| `issue_accepted` | 200 + status=ACCEPTED | 정상 |
| `issue_duplicate` | 200 + status=DUPLICATE | 정상 (1 인 1 장 제약 자연 차단) |
| `issue_internal_error` | 503 (CB OPEN / B 일시 장애) | 작아야 함 |
| `issue_other` | 그 외 | 0 이어야 함 (>0 시 결함 신호) |

### 4.2 통과 기준 (thresholds)

- `http_req_duration p95 < 200ms` — Redis 적재 + Kafka publish 의 합리적 한계
- `http_req_duration p99 < 400ms` — long tail 허용 (cold start GC 등)
- `http_req_failed rate < 0.005` — 0.5% 이하 (Circuit Breaker 일시 OPEN 허용)

### 4.3 관측

부하 중 다음 지표를 함께 모니터링:

- Grafana http://localhost:3000 (admin/admin) — JVM heap / CPU / HTTP p95 / HikariCP / Kafka consumer lag
- Kafka UI http://localhost:8085 — `coupon-issue-request` / `coupon-issue-result` lag

---

## 5. 트러블슈팅

| 증상 | 원인 | 해결 |
|---|---|---|
| 시나리오 첫 ~수초 503 다발 | Cold start, CB sliding window 가 slow call 로 OPEN | warmup 필요 — 시나리오 시작 전 1~2회 단발 호출 |
| 모두 DUPLICATE | 이전 run 의 Redis pending 잔존 | `docker exec promotion-redis redis-cli FLUSHDB` |
| `issue_other > 0` | 4xx — body invalid 또는 마스터 데이터(event/coupon_type) 미시드 | §1.3 시드 SQL 실행 |
| C consumer lag 폭증 | 1 vCPU MySQL-C 의 비관적 락 처리량 한계 도달 | `app.kafka.consumer.concurrency`/`max-poll-records` 조정 또는 인스턴스 추가 |
