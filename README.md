# promotion

5일 일정의 백엔드 채용 과제. **콘서트 사전 예매 할인 쿠폰 — 선착순 10,000장** 시나리오를
1 vCPU / 2 GB RAM 노드 3대 (Server A / B / C) 로 처리하는 분산 시스템 구현입니다.

핵심 명제: 인프라 제약을 **소프트웨어(큐 + 비동기 + 백프레셔 + 분산 캐시)** 로 푼다.

## 구현 범위

- 발급 (Issue) — Server A 진입점, Idempotency-Key, Rate Limit, Circuit Breaker
- 재고 관리 — Server B Redis Lua atomic 차감, 샤딩 (Hot Spot 회피)
- 영구 저장 — Server C Kafka consume + UNIQUE constraint 멱등성
- 사용 (Redeem) — Server C 낙관적 락

## 빠른 실행

```bash
docker compose up -d                                     # mysql + redis + kafka
./gradlew clean build -x test
./gradlew :server-b:bootRun --args='--spring.profiles.active=local' &
./gradlew :server-a:bootRun &                            # default profile (RestClient → 8081)
./gradlew :server-c:bootRun &                            # Kafka consumer 활성
```

## 문서

### 아키텍처

- [01. 아키텍처 개요](docs/architecture/01.Architecture-Overview.md)
- 02. 도메인 설계 (Day 2~3 작성 예정)
- 03. 시퀀스 다이어그램 (Day 2~3 작성 예정)
- 04. ERD / JPA 설계 (Day 2~3 작성 예정)
- 05. API 설계 (Day 2~3 작성 예정)
- 06. 설계 결정 / 트레이드오프 (Day 2~5 작성 예정)

### 보고서

- 01. 부하 테스트 결과 (Day 4 작성 예정)
- 02. 100,000명 사이징 계산 (Day 5 작성 예정)

## 검증 — 통합 e2e (k6)

`server-a` + `server-b` + `server-c` + MySQL + Redis + Kafka 가 모두 기동된 단일 환경에서
12개 시나리오 (phase9-01..06 + day2-04 burst + day3-01..05) 를 한 번의 명령으로 검증합니다.
assertion 기반 회귀 — 본격 1 vCPU 부하는 Day 4 capacity-planning 의 영역.

```bash
brew install k6                                    # macOS
docker compose up -d mysql redis kafka             # 인프라

# 세 서비스 모두 기동
./gradlew :server-b:bootRun --args='--spring.profiles.active=local' &
./gradlew :server-a:bootRun &                      # default profile — RestClient 활성, base-url=8081
./gradlew :server-c:bootRun &                      # default profile — Kafka consumer 활성

# 헬스체크
curl -sS http://localhost:8080/actuator/health
curl -sS http://localhost:8081/actuator/health
curl -sS http://localhost:8082/actuator/health

# 통합 시나리오 일괄 실행 (stock seed + truncate + CB warmup + 12 시나리오)
./load-test/run-integrated.sh
```

`run-integrated.sh` 가 자동 수행:
1. Redis FLUSHDB + server_a/b/c schema TRUNCATE
2. Redis 샤드 10 × 100 = 1,000 stock seed
3. server-a/b/c 헬스체크 (모두 UP 확인)
4. server-a CB warmup curl 5번 (cold start 시 read-timeout 200ms 초과로 OPEN 되는 함정 회피)
5. phase9-01..06 + day2-04-burst + day3-01..05 일괄 실행
6. Outbox 행 수 + server_c.coupon 행 수 + used_at NOT NULL 행 수 사후 출력

자세한 사전 조건 / 환경변수 / 결과 해석 / 트러블슈팅은 [`load-test/README.md`](./load-test/README.md) 참조.

### Stock seed (수동)

운영 endpoint 미구현 — `redis-cli` 직접 SET 또는 cli 도구 영역 (본 과제 미포함).

```bash
for i in 0 1 2 3 4 5 6 7 8 9; do
  docker exec promotion-redis redis-cli SET "event:1:stock:$i" 100
done
```

### 알려진 trade-off

- **자기 샤드 SOLD_OUT 시 다른 샤드 fallback 없음**: 사용자 hash 가 자기 샤드만 본다. 다른 샤드에 재고가 남아있어도 해당 사용자는 SOLD_OUT 응답.
- **유령 재고 (B JVM 크래시)**: Lua ISSUED 직후 / Outbox INSERT 직전에 크래시 시 Redis 차감 + MySQL 미INSERT. reconciliation job 미구현 — 프로덕션 진화 방향.
- **운영 Stock 분배 endpoint 없음**: `StockSeeder` 빈은 테스트 setup 용. 운영에서는 별도 admin endpoint 또는 cli 도구 필요.
- **Cold start CB OPEN 가능성**: 첫 호출이 read-timeout 200ms 를 초과할 수 있음. `run-integrated.sh` 가 명시적 warmup curl 로 sliding-window 정상화 후 main 시나리오 시작.
- **Server A 의 요청 로그 batch insert / 명시적 백프레셔 미적용 (Day 4 측정 후 결정)**: CLAUDE.md §3 평가 ① / ADR-005 가 권고하나 "감으로" 도입하지 않는다. Day 4 의 k6 부하 측정으로 병목을 정량 식별한 뒤 도입 여부 + 구현 형태를 결정 — 근거: [`docs/decisions/server-a-tuning-load-test-driven.md`](docs/decisions/server-a-tuning-load-test-driven.md).
- **Server C consumer DLT 미구현**: 일시적 DB 오류는 Spring Kafka `DefaultErrorHandler` 의 default retry, UNIQUE 위반 / 잘못된 payload 는 ack-and-skip 으로 흡수. retry 모두 실패 시 stop → 운영 알림 + 수동 복구. CDC + DLT 는 프로덕션 진화 방향.
- **Server C `concurrency: 1`**: 1 vCPU 환경에서 컨텍스트 스위칭 비용 > 병렬 이득. partition 3 개를 단일 consumer 가 처리 → 인스턴스 처리량은 partition 1개 단위 처리 속도에 묶임. 운영 진화 시 `concurrency=3` (인스턴스 수와 별개로 listener thread).
- **IT 환경 정책 차이**: `server-c CouponConsumerIT` 는 EmbeddedKafka (in-process broker) 를 쓰고 `server-b OutboxPollerIT` 는 host docker-compose Kafka 를 쓴다. 의도가 다름 — 전자는 "실 메시지 → consume → DB 영속" 전 흐름 검증이라 broker 가 필수, 후자는 "트랜잭션 분리 + SKIP LOCKED + markPublished" 만 검증하므로 publisher 를 mock 처리. EmbeddedKafka 는 사전 docker compose up 없이 `./gradlew test` 한 줄로 통과.
- **Server C redeem 의 idempotency 는 도메인 자체 멱등**: Idempotency-Key 헤더는 trace/log 용이고 별도 캐시/테이블 없음. 같은 user 의 같은 coupon 재호출은 본질적으로 같은 결과 (`used_at` 한 번 set 후 영구) — `newlyRedeemed=false` flag 로 첫 호출과 구분. 발급(issue) 의 강한 멱등 (캐시 + DB UNIQUE) 과 다른 정책. ADR-004 의 user-scoped 적용 범위는 동일하나 보장 강도가 다름.
- **Server C redeem 의 소유권 마스킹**: 다른 user 의 쿠폰 시도와 "코드 없음" 모두 404 동일 응답. 코드 존재 여부 누설 방지. 운영 디버깅 시에는 log.warn 의 owner/requester 로 구분 가능.
- **Server C `ApiResponse` / `ErrorResponse` / `GlobalExceptionHandler` 가 server-a 와 중복**: 후속 PR 에서 common 모듈로 통합 예정. 현재 두 서버가 동일 형식으로 진화 중이라 표류 위험은 작지만 명시적 통합 권장.

## 실행 정보

### 애플리케이션

- Server A: `http://localhost:8080/actuator/health`
- Server B: `http://localhost:8081/actuator/health`
- Server C: `http://localhost:8082/actuator/health`
- Prometheus: `http://localhost:8080/actuator/prometheus` / `http://localhost:8081/actuator/prometheus` / `http://localhost:8082/actuator/prometheus`

### 인프라

- MySQL `3306` (root / `rootpassword`, schemas: `server_a`, `server_b`, `server_c`)
- Redis `6379`
- Kafka `9092` (컨테이너 간 INTERNAL) / `29092` (호스트 HOST)
- Kafka UI `http://localhost:8085`

자세한 명령어와 검증 절차는 [01. 아키텍처 개요 §6 로컬 실행](docs/architecture/01.Architecture-Overview.md#6-로컬-실행) 참조.

## 핵심 설계 포인트

- A→B 동기 + B→C 비동기로 즉시 응답 + 영구 저장 분리 (ADR-001)
- Redis Lua 기반 atomic 재고 차감, 10 샤드로 Hot Spot 회피 (ADR-003)
- 삼중 멱등성 방어: Server A `(user_id, idempotency_key) UNIQUE` + Server B Redis user-scoped 1차 캐시 + Server C `(user_id, idempotency_key) UNIQUE` + `code UNIQUE` (ADR-004 일관 적용)
- Outbox poller + Kafka producer (`acks=all + enable.idempotence=true`) + Server C UNIQUE constraint = 의미적 exactly-once (ADR-002)
- Bucket4j Lettuce backend, 사용자당 10 req/sec (ADR-005)
- redeem 낙관적 락 (`@Version`), 비관 락 회피 (ADR-007)

상세 근거는 [01. 아키텍처 개요 §5 핵심 설계 결정](docs/architecture/01.Architecture-Overview.md#5-핵심-설계-결정-adr) 참조.

## 진행 상황

### Day 1 — Server A 단독 동작 (완료)

| PR | 내용 | 상태 |
|---|---|---|
| #1 | Gradle 멀티모듈 + docker-compose | merged |
| #2 | 도메인 모델 + Flyway | merged |
| #3 | README 골격 + Mermaid | merged |
| #4 | docs/architecture/ 분리 | merged |
| #5 | Server A 발급 API + Stub 클라이언트 | merged |
| #6 | Idempotency + Rate Limit Filter | merged |
| #7 | RestClient + Circuit Breaker (+ k6 Phase 9) | merged |

### Day 2 — Server B (재고 + Outbox + A↔B 통합)

| PR | 내용 | 상태 |
|---|---|---|
| #8  | Server B 부트스트랩 + Outbox 인프라 | merged |
| #9  | docs(decisions): Outbox 전략 결정 근거 | merged |
| #10 | Redis Lua atomic 발급 + 보상 트랜잭션 + 동시성 IT | merged |
| #11 | A↔B 실통합 (`base-url` 정정) + Stub SOLD_OUT hook + k6 day2 4 시나리오 | merged |
| #12 | Server B idempotency 캐시/Outbox UNIQUE user-scoped (ADR-004 정합) | merged |
| #13 | Server A 핵심 로직 단위 테스트 보강 (44 cases) | merged |

### Day 3 — Server C + Outbox/Saga (진행 중)

| PR | 내용 | 상태 |
|---|---|---|
| #14 | Server B Outbox poller + Kafka producer | merged |
| #15 | docs(decisions): Server A batch insert / 백프레셔는 Day 4 부하 측정 후 결정 | merged |
| #16 | Server C Kafka consumer + UNIQUE 멱등성 (V2 user-scoped 정합) | merged |
| #17 | Server C Redeem API + 낙관적 락 (ADR-007) | merged |
| #18 | e2e k6 day3 시나리오 + run-integrated.sh 확장 | in progress |

전체 5일 로드맵: [`CLAUDE.md` §12](./CLAUDE.md).
