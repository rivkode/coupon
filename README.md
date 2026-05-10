# promotion

**이벤트별 선착순 할인 쿠폰 발급 및 사용** 시스템을 1 vCPU / 2 GB RAM 노드 3 대 (Server A / B / C) 로 처리하는 분산 시스템 구현.

핵심 명제: 인프라 제약(1 vCPU) 을 **소프트웨어 (비동기 + 비관적 락 + Kafka throttle + Outbox)** 로 푼다.

설계 출처: [`docs/rivkode_system_design.md`](docs/rivkode_system_design.md) (source of truth)

---

## 평가자 가이드

본 README 는 entry point. 상세는 카테고리별 폴더에 분리.

### 1) 5 축 평가 항목 → 어디서 다뤘는가

| # | 평가 항목 | 핵심 기법 | 코드 위치 |
|---|---|---|---|
| ① | 대량 트래픽 + 동시성 | A 진입 단순화 + per-request commit / C 의 1 트랜잭션 비관적 락 + Outbox | `server-a/.../IssueRequestService`, `server-c/.../CouponIssueProcessor` |
| ② | 분산 정합성 + 멱등성 | Saga(choreography) + C 의 Outbox + `(user_id, coupon_type_id)` UNIQUE | `server-c/.../OutboxPoller`, `server-c/.../UserCouponJpaEntity` (UNIQUE) |
| ③ | 캐시 + Hot Spot | Redis 의 pending 신청 적재 + `event:{id}` Cache-Aside (TTL) | `server-b/.../RedisIssueRequestStore`, `server-b/.../EventCacheService` |
| ④ | Rate Limit / Backpressure | Kafka consumer throttle (`max.poll.records` / `concurrency`) + A↔B Resilience4j Circuit Breaker + B 의 `@Scheduled` 보완 | `server-c/.../KafkaConfig`, `server-a/.../RestClientCouponIssuingClient`, `server-b/.../PendingIssueScheduler` |
| ⑤ | 인프라 사이징 | k6 부하 + Little's Law 기반 인스턴스 산정 | `load-test/scenarios/` |

### 2) 빠르게 보고 싶은 것

- **신규 설계 출처** → [`docs/rivkode_system_design.md`](docs/rivkode_system_design.md)
- **아키텍처 개요 + 데이터 흐름 + 5 축 매핑** → [`docs/architecture/01.Overview.md`](docs/architecture/01.Overview.md)
- **결정 (ADR) 본문** → [`CLAUDE.md` §6](./CLAUDE.md) (인덱스: [`docs/decisions/README.md`](docs/decisions/README.md))
- **부하 검증** → [`load-test/README.md`](load-test/README.md)

---

## 인증

본 과제는 단순화를 위해 **JWT 등 외부 인증 시스템을 도입하지 않고 `X-User-Id` 헤더로 대체**합니다. 모든 API 호출은 `X-User-Id` 헤더로 사용자를 식별하며, 상위 게이트웨이(API Gateway / OAuth2 Resource Server 등)가 인증 후 user_id 를 추출해 헤더로 전달하는 운영 모델을 가정합니다.

```
Authorization: Bearer <jwt>     ← (운영) 상위 게이트웨이가 검증
                ↓ 검증 + user_id 추출
X-User-Id: <user_id>            ← 본 시스템이 받는 형태
```

평가 환경에서는 클라이언트가 직접 `X-User-Id` 헤더를 보냅니다.

---

## 발급 요청 (User → Server A)

`POST /api/v1/coupons/issue-request`

| 헤더 | 필수 | 의미 |
|---|---|---|
| `X-User-Id` | ✅ | 사용자 식별자 (JWT 대체 — 위 §인증 참조) |

**body — 10 필드** (필수: country, eventId, couponTypeId, channel, issuedAt, expireAt / 선택: deviceId, clientVersion, language, marketingConsent)

```json
{
  "country": "KR",
  "eventId": 202605,
  "couponTypeId": 1,
  "issuedAt": "2026-05-09T20:00:00Z",
  "expireAt": "2026-06-09T23:59:59Z",
  "channel": "APP",
  "deviceId": "abc-12345",
  "clientVersion": "3.2.1",
  "language": "ko",
  "marketingConsent": true
}
```

응답: 즉시 `200 OK` + `{ requestId, status: ACCEPTED|DUPLICATE }`. 실제 발급 결과는 폴링/내정보 조회로 비동기 확인.

---

## 시스템 아키텍처

```
[User] ──HTTPS──▶ [Server A] ──sync HTTP──▶ [Server B] ──Kafka(issue)──▶ [Server C]
                  진입/요청 로그              Redis 적재 / 즉시 응답         재고 차감 / 영구 저장
                       │                          │   ▲                       │
                    [MySQL-A]                  [Redis] │                   [MySQL-C]
                  issue_request               pending  │ Kafka(result)       event 마스터
                  (per-request commit)        user 신청 ◀─────────────────── coupon_type
                                              event cache                    coupon_type_inventory
                                              @Scheduled (10s)               user_coupon
                                                                             outbox_event
```

**핵심 명제**

- **응답 모델**: 사용자는 발급 요청 시 즉시 "접수 완료" 만 받고, 결과는 폴링/내정보 조회로 비동기 확인
- **재고 권위**: Server C 의 `coupon_type_inventory` row + `SELECT ... FOR UPDATE` 비관적 락
- **멱등성**: `(user_id, coupon_type_id)` UNIQUE 가 1 인 1 장 + 중복 Kafka 메시지를 동시에 보장
- **Backpressure**: B → C Kafka consumer 의 `max.poll.records` / `concurrency` 로 1 vCPU MySQL-C 가 처리 가능한 양만 흘림
- **Kafka 양방향**: B → C (발급 신청) / C → B (발급 결과 → Redis 캐시 갱신) — 사용자 폴링 시 B 의 Redis 만 조회
- **보완 메커니즘**: B 의 `@Scheduled` 가 10 초 이상 pending 인 신청을 C 의 internal GET API 로 직접 조회 → Kafka 메시지 유실 방어

---

## 도메인 모델

### Server A (MySQL — schema `server_a`)
- `issue_request` (request_id, user_id, event_id, coupon_type_id, status, created_at)

### Server B (Redis only)
- `issue:pending:{user_id}:{coupon_type_id}` — Hash (status / created_at / event_id / request_id)
- `issue:pending:zset` — Sorted Set, score = created_at (스케줄러 ZRANGEBYSCORE)
- `event:{event_id}` — Hash (cache, TTL)

### Server C (MySQL — schema `server_c`)
- `event`, `coupon_type`, `coupon_type_inventory`, `user_coupon`, `outbox_event`
- UNIQUE: `user_coupon (user_id, coupon_type_id)`, `user_coupon (code)`

### Kafka 토픽
- `coupon-issue-request` (B → C)
- `coupon-issue-result` (C → B)

---

## 빠른 실행

평가 5 축 ⑤(사이징) 의 전제인 **각 서비스 1 vCPU / 2 GB RAM** 을 컨테이너 단위로 강제. 부하 측정 신뢰성을 위해 본 모드 권장.

```bash
# 1) bootJar 빌드 (호스트에서)
./gradlew clean :server-a:bootJar :server-b:bootJar :server-c:bootJar -x test

# 2) 인프라 + 3 서비스 부팅 (각 서비스 cpus=1 + mem=2g)
docker compose up -d --build

# 3) 헬스 확인
docker compose ps
curl -sS http://localhost:8080/actuator/health   # server-a
curl -sS http://localhost:8081/actuator/health   # server-b
curl -sS http://localhost:8082/actuator/health   # server-c

# 4) 검증 — k6 통합 시나리오
./load-test/run-integrated.sh

# 5) 관측성 — Grafana 대시보드
#    http://localhost:3000  (admin/admin) → Promotion 폴더 → Promotion Overview
```

### 개발 편의 — bootRun 모드 (자원 제약 없음)

```bash
docker compose up -d mysql-a mysql-c redis kafka         # 인프라만
./gradlew :server-c:bootRun &
./gradlew :server-b:bootRun --args='--spring.profiles.active=local' &
./gradlew :server-a:bootRun &
./load-test/run-integrated.sh
```

> bootRun 모드는 사이징 측정에는 부적합. 코드 변경 후 빠른 검증용.

상세 절차 / 환경변수 / 트러블슈팅: [`load-test/README.md`](load-test/README.md)

---

## 핵심 결정 요약

전체 ADR 은 [`CLAUDE.md` §6](./CLAUDE.md) 와 [`docs/decisions/`](docs/decisions/) 참조.

| ADR | 결정 | 근거 |
|---|---|---|
| 001 | A→B 동기 호출, B 는 Redis 적재 + Kafka publish 후 즉시 "접수 완료" 응답 | 사용자 응답 latency 와 실제 발급 처리(C 의 비관적 락) 분리 |
| 002 | Saga (choreography) + Outbox 는 C 에 위치 | orchestrator 부담 회피, choreography 가 1 vCPU 에 경량 |
| 003 | 재고는 C MySQL `coupon_type_inventory` + `SELECT FOR UPDATE` | 발급은 비동기 처리 → 락 경합과 사용자 응답이 분리. consumer concurrency 를 1~2 로 두면 1 vCPU 에서도 안전 |
| 004 | 멱등성은 `(user_id, coupon_type_id)` UNIQUE 만 (Idempotency-Key 헤더 미사용) | 1 인 1 장 제약 자체가 멱등성을 만족 |
| 005 | A 의 사용자별 Rate Limit 미사용 | 1 인 1 장 UNIQUE 가 자연 차단. Backpressure 는 B → C consumer throttle 로 |
| 006 | Database per Service (A=MySQL, B=Redis, C=MySQL, schema 분리) | 서비스 독립 배포, 결합도 감소 |
| 007 | Redeem 은 `@Version` 낙관적 락 | 발급 대비 동시 redeem 은 드묾, 비관적 락은 오버헤드 |
| 008 | B 의 Kafka publish 실패는 producer 재시도 + `@Scheduled` 10 초 보완 | UNIQUE 가 중복 publish 방어, B 에 RDBMS 추가 회피 |
| 009 | B↔C 양방향 Kafka (`coupon-issue-request` / `coupon-issue-result`) | 사용자 폴링이 B Redis 만 보면 되도록 결과를 B 에 캐시 |
| 010 | A 의 요청 로그는 per-request commit (batch insert 아님) | 응답이 즉시 "접수 완료" 라 짧음 → 별도 큐 불필요. 1 vCPU MySQL-A 처리량은 측정 후 결정 |

---

## 실행 정보

| 서비스 | 포트 | 헬스 |
|---|---|---|
| Server A | `:8080` | `/actuator/health` |
| Server B | `:8081` | `/actuator/health` |
| Server C | `:8082` | `/actuator/health` |
| MySQL-A | `:3306` | schema `server_a` (server-a 전용, 컨테이너 `promotion-mysql-a`) |
| MySQL-C | `:3307` | schema `server_c` (server-c 전용, 컨테이너 `promotion-mysql-c`) |
| Redis | `:6379` | — |
| Kafka | `:9092` (INTERNAL) / `:29092` (HOST) | — |
| Kafka UI | `http://localhost:8085` | — |
| Prometheus | `http://localhost:9090` | — |
| Grafana | `http://localhost:3000` (admin/admin) | — |

---

## 검증

### 단위 테스트 (mock 기반 — 빠른 비즈니스 로직 / 도메인 invariant)

| 테스트 | 검증 대상 |
|---|---|
| `IssueRequestServiceTest` | Server A 진입 흐름 — ACCEPTED/DUPLICATE/INTERNAL_ERROR 매핑 + 200 동시 트래픽 일관성 (mock) |
| `CouponIssueAcceptServiceTest` | Server B 접수 — 중복 차단 + Kafka publish 실패 시 pending 보존 (mock) |
| `PendingIssueSchedulerTest` | Server B `@Scheduled` 보완 — 4 분기 동작 (mock) |
| `CouponIssueProcessorTest` | Server C 비관적 락 경로 호출 + 1 트랜잭션 흐름 + UNIQUE race 흡수 (mock) |
| `RedeemCouponServiceTest` | Redeem 정책 — 멱등 replay / ownership masking / SOLD_OUT 차단 |
| `CouponTypeInventoryJpaEntityTest` | 재고 차감 invariant |
| `UserCouponJpaEntityTest` | Redeem 도메인 invariant (status 전이 / usedAt) |
| `IssueRequestTest` | Server A audit 도메인 |
| `RedisKeysTest` | Redis 키 컨벤션 |

### 통합 테스트 (실제 인프라 — mock 으론 검증 불가능한 영역)

| 테스트 | 인프라 | 검증 대상 |
|---|---|---|
| `CouponIssueProcessorConcurrencyIT` | MySQL 8.0 (Testcontainers) | **비관적 락 정합성** — 재고 100 / 동시 200 요청 → 정확히 100 SUCCESS + 100 SOLD_OUT + `available_count=0` / 같은 user 5 동시 → 1 건만 발급 |
| `IssueRequestServiceIT` | MySQL 8.0 (Testcontainers) + WireMock(B) | **per-request commit** 200 동시 → 200 row / **CB OPEN 전이** 5xx 10회 후 state=OPEN / **read-timeout** fallback + REJECTED commit |
| `CouponIssueAcceptServiceIT` | Redis 7 (Testcontainers) + EmbeddedKafka | **Redis HSETNX atomic** 동시 10 호출 → 1 건만 ACCEPTED + Kafka 1 메시지 / Kafka 토픽 round-trip |
| `CouponIssueResultConsumerIT` | Redis 7 (Testcontainers) + EmbeddedKafka | **C→B Kafka round-trip** — result publish → listener → Redis hash 갱신 + zset 제거 |
| `PendingIssueSchedulerIT` | Redis 7 (Testcontainers) + WireMock(C) | **`@Scheduled` 시간축 동작** — C=200 → SUCCESS / C=404 → FAILED / C=5xx → PENDING 유지 (다음 cycle 재시도) |

```bash
./gradlew test
```

> 통합 테스트는 모두 **`docker daemon` 필요**. Testcontainers 가 ad-hoc 으로 MySQL/Redis 컨테이너를 부팅하고, Spring Boot context 위에 EmbeddedKafka / WireMock 을 결합해 단위 테스트로는 닿을 수 없는 영역(InnoDB row lock 정합성, Redis HSETNX atomicity, Resilience4j 상태 전이, Kafka round-trip, `@Scheduled` 시간축) 을 측정합니다.

### k6 부하 시나리오
- `load-test/scenarios/issue-1k-tps.js` — 인스턴스당 1,000 TPS 발급 부하 (p95 < 200 ms / p99 < 400 ms / 5xx < 0.5%)

```bash
./load-test/run-integrated.sh
```

### 관측성
- Prometheus + Grafana 대시보드 (JVM heap / CPU / HTTP p95 / HikariCP active / Kafka consumer lag)

---

## 문서 구조

- [`CLAUDE.md`](./CLAUDE.md) — 프로젝트 컨텍스트 + ADR 본문
- [`docs/rivkode_system_design.md`](docs/rivkode_system_design.md) — 신규 설계 출처 (source of truth)
- [`docs/architecture/`](docs/architecture/) — 아키텍처 개요 / 데이터 흐름 / Kafka 토픽
- [`docs/decisions/README.md`](docs/decisions/README.md) — ADR 인덱스 (본문은 CLAUDE.md)
- [`load-test/`](load-test/) — k6 시나리오 + 실행 스크립트
