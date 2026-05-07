# promotion

**콘서트 사전 예매 할인 쿠폰 — 선착순 10,000장** 시나리오를 1 vCPU / 2 GB RAM 노드 3대 (Server A / B / C) 로 처리하는 분산 시스템 구현.

핵심 명제: 인프라 제약을 **소프트웨어 (큐 + 비동기 + 백프레셔 + 분산 캐시)** 로 푼다.

---

## 평가자 가이드

본 README 는 entry point 역할만. 각 영역의 상세는 카테고리별 폴더에 분리되어 있습니다.

### 1) 5축 평가 항목 → 어디서 다뤘는가

| # | 평가 항목 | 핵심 기법 | 코드 위치 | 결정 문서 | k6 검증 |
|---|---|---|---|---|---|
| ① | 대량 트래픽 + 동시성 | HikariCP, batch insert (Day 4 결정) | `server-a/.../IssueRequestService` | [server-a-tuning](docs/decisions/server-a-tuning-load-test-driven.md) | `day2-04-burst.js` |
| ② | 분산 정합성 + 멱등성 | Saga + Outbox + UNIQUE 3 단계 | `server-b/.../OutboxPoller`, `server-c/.../CouponConsumer` | [outbox-mysql-vs-redis-streams](docs/decisions/outbox-mysql-vs-redis-streams.md), [server-b-outbox-poller-kafka](docs/decisions/server-b-outbox-poller-kafka.md) | `day3-01..04` |
| ③ | Hot Spot 회피 | Redis 재고 10 샤드 + Lua atomic | `server-b/.../RedisStockClient`, `issue-coupon.lua` | [`04.Data-Stores`](docs/architecture/04.Data-Stores.md) §3 | (Day 4 측정) |
| ④ | Rate Limit / Backpressure | Bucket4j Lettuce + Resilience4j CB | `server-a/.../RateLimitFilter`, `CouponIssuingRestClient` | CLAUDE.md ADR-005 | `phase9-04-rate-limit.js` |
| ⑤ | 인프라 사이징 | k6 부하 + Little's Law | (Day 4) | [server-a-tuning](docs/decisions/server-a-tuning-load-test-driven.md) | (Day 4) |

### 2) 가장 보고싶은 부분만 빨리 보기

- **시스템 한눈** → [아키텍처 개요](docs/architecture/01.Overview.md) (5분)
- **핵심 흐름 시각화** → [시퀀스 다이어그램](docs/architecture/03.Sequence-Diagrams.md) (정상 / 보상 / SOLD_OUT / redeem race)
- **결정 + 거부된 대안** → [결정 (ADR) 인덱스](docs/decisions/README.md)
- **장애 시나리오 (각 단계에서 죽으면?)** → [`failure-modes.md`](docs/runbooks/failure-modes.md)
- **의도적으로 안 한 것** → [`scope-decisions.md`](docs/decisions/scope-decisions.md)
- **검증** → [`load-test/README.md`](load-test/README.md) — 12개 e2e 시나리오 한 번에 실행

---

## 빠른 실행 (권장 — 자원 제약 강제)

평가 5축 ⑤ (사이징) 의 전제인 **각 서비스 1 vCPU / 2 GB RAM** 을 컨테이너 단위로 강제. 부하 측정 / 사이징 검증의 신뢰성을 위해 본 모드로 실행 권장.

```bash
# 1) bootJar 빌드 (호스트에서)
./gradlew clean :server-a:bootJar :server-b:bootJar :server-c:bootJar -x test

# 2) 인프라 + 3 서비스 한 번에 부팅 (각 서비스 cpus=1 + mem=2g 강제)
docker compose up -d --build

# 3) 헬스 확인 (모두 UP 까지 ~30~60초)
docker compose ps           # 모든 컨테이너 healthy
curl -sS http://localhost:8080/actuator/health   # server-a
curl -sS http://localhost:8081/actuator/health   # server-b
curl -sS http://localhost:8082/actuator/health   # server-c

# 4) 검증 — 12개 e2e 시나리오
./load-test/run-integrated.sh
```

### 개발 편의 — bootRun 모드 (자원 제약 없음, 빠른 부팅)

```bash
docker compose up -d mysql redis kafka                   # 인프라만
./gradlew :server-b:bootRun --args='--spring.profiles.active=local' &
./gradlew :server-a:bootRun &
./gradlew :server-c:bootRun &
./load-test/run-integrated.sh
```

> bootRun 모드는 호스트 자원을 무제한 사용 — **사이징 / 부하 측정에는 부적합**. 코드 변경 후 빠른 검증용. 평가 시나리오 검증은 위의 docker compose 모드 사용.

상세 절차 / 환경변수 / 트러블슈팅: [`load-test/README.md`](load-test/README.md)

---

## 문서 구조

### 아키텍처 (`docs/architecture/`)
- [01. 개요](docs/architecture/01.Overview.md) — 시스템 다이어그램 + 데이터 흐름 + 5축 매핑 (5분)
- [02. 도메인 모델](docs/architecture/02.Domain-Model.md) — Aggregate / VO / 상태 전이 / ERD
- [03. 시퀀스 다이어그램](docs/architecture/03.Sequence-Diagrams.md) — 발급 / 보상 / SOLD_OUT / redeem race
- [04. 데이터 저장소](docs/architecture/04.Data-Stores.md) — MySQL schema / Redis 키 / Kafka 토픽
- [05. 모듈 구조](docs/architecture/05.Modules.md) — Gradle 멀티 모듈 / 패키지 레이어

### 결정 (`docs/decisions/`)
- [00. ADR 인덱스](docs/decisions/README.md) — CLAUDE.md ADR 7개 + 추가 결정 6개
- [Outbox 전략 (MySQL vs Redis Streams)](docs/decisions/outbox-mysql-vs-redis-streams.md)
- [Server B idempotency cache user-scoped](docs/decisions/server-b-user-scoped-idempotency-cache.md)
- [Outbox poller + Kafka producer](docs/decisions/server-b-outbox-poller-kafka.md)
- [Server A 튜닝 — 측정 후 결정](docs/decisions/server-a-tuning-load-test-driven.md)
- [Redeem 멱등 — 별도 캐시 미도입](docs/decisions/redeem-idempotency-without-cache.md)
- [Scope — 의도적으로 안 한 것](docs/decisions/scope-decisions.md)

### 운영 / 장애 (`docs/runbooks/`)
- [장애 시나리오](docs/runbooks/failure-modes.md) — 각 단계에서 죽으면 어떻게 되는가

통합 e2e 검증 절차 (12 시나리오) 는 [`load-test/README.md`](load-test/README.md) 가 단일 진실. runbook 으로 분리하지 않음 (한 곳에서 관리).

### 보고서 (`docs/reports/`)
- 부하 테스트 결과 (Day 4 작성 예정)
- 100,000명 사이징 계산 (Day 5 작성 예정)

### 분석 / 기획
- [요구사항 분석](docs/analysis/requirements-analysis.md)
- [PRD](docs/prd/promotion-prd.md)

전체 5일 로드맵: [`CLAUDE.md` §12](./CLAUDE.md)

---

## 실행 정보

| 서비스 | 포트 | 헬스 |
|---|---|---|
| Server A | `:8080` | `/actuator/health` |
| Server B | `:8081` | `/actuator/health` |
| Server C | `:8082` | `/actuator/health` |
| MySQL | `:3306` | schemas: `server_a`, `server_b`, `server_c` |
| Redis | `:6379` | — |
| Kafka | `:9092` (INTERNAL) / `:29092` (HOST) | — |
| Kafka UI | `http://localhost:8085` | — |
| Prometheus | 각 서비스 `/actuator/prometheus` | — |

---

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

### Day 2 — Server B (재고 + Outbox + A↔B 통합) (완료)

| PR | 내용 | 상태 |
|---|---|---|
| #8  | Server B 부트스트랩 + Outbox 인프라 | merged |
| #9  | docs(decisions): Outbox 전략 결정 근거 | merged |
| #10 | Redis Lua atomic 발급 + 보상 트랜잭션 + 동시성 IT | merged |
| #11 | A↔B 실통합 (`base-url` 정정) + Stub SOLD_OUT hook + k6 day2 4 시나리오 | merged |
| #12 | Server B idempotency 캐시/Outbox UNIQUE user-scoped (ADR-004 정합) | merged |
| #13 | Server A 핵심 로직 단위 테스트 보강 (44 cases) | merged |

### Day 3 — Server C + Outbox/Saga + e2e (완료)

| PR | 내용 | 상태 |
|---|---|---|
| #14 | Server B Outbox poller + Kafka producer | merged |
| #15 | docs(decisions): Server A batch insert / 백프레셔는 Day 4 부하 측정 후 결정 | merged |
| #16 | Server C Kafka consumer + UNIQUE 멱등성 (V2 user-scoped 정합) | merged |
| #17 | Server C Redeem API + 낙관적 락 (ADR-007) | merged |
| #18 | e2e k6 day3 시나리오 + run-integrated.sh 확장 | merged |
| #19 | docs: 평가자 가이드 + 시퀀스 + 장애 시나리오 + ADR 보강 + 컨테이너 자원 제약 (1 vCPU / 2 GB) | in progress |

### Day 4~5 — 부하 측정 + 사이징 (예정)

- 1 vCPU 부하 측정 (smoke / load / stress / spike) + Server A 튜닝 결정 (batch insert / 백프레셔)
- Little's Law 기반 100,000명 사이징 계산
