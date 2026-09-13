# 프로모션 시스템

> 이 문서는 Claude Code 가 매 세션마다 자동으로 읽는 프로젝트 컨텍스트입니다.
> 새 작업을 시작하기 전에 이 문서의 결정사항을 반드시 준수해 주세요.
> 결정사항을 변경해야 한다고 판단되면, 먼저 사용자와 상의하세요.
>
> 본 문서의 출처(source of truth)는 `docs/rivkode_system_design.md` 입니다. 두 문서가 충돌하면 `rivkode_system_design.md` 가 우선합니다.

---

## 1. 프로젝트 개요

- **목적**: 백엔드 채용 과제 (프로모션 도메인)
- **핵심 평가 포인트**: 제한된 자원(1 vCPU, 2 GB RAM) 에서 대량 트래픽을 안정적으로 처리하는 분산 시스템 설계 및 구현
- **시나리오**: **이벤트별 선착순 할인 쿠폰 발급 및 사용**
    - 이벤트 100 개 × 이벤트별 쿠폰 **100 장** (사용자 1,000 명 대비 재고를 적게 두어 매진이 반드시 발생하는 조건. 요청의 대부분이 매진 이후에 도착한다)
    - 사용자는 이벤트당 1 장만 발급 가능 (`(user_id, coupon_type_id)` UNIQUE)
    - 발급(Issue): 사용자가 쿠폰을 청구하는 행위 (메인 트래픽)
    - 사용(Redeem): 발급받은 쿠폰을 결제 등에서 소비하는 행위 (보조 기능)
- **응답 모델**: 사용자는 발급 요청 시 **"접수 완료"** 만 즉시 응답으로 받고, 실제 발급 결과는 폴링/내정보 조회로 비동기 확인 (이벤트 기반 일관성)

---

## 2. 트래픽 시나리오 및 자원 제약

| 항목 | 값 |
|------|------|
| 총 사용자 (피크) | 1,000 명 |
| 사용자당 요청 | 10 초 내 10 건 (사용자당 100 건은 10 초 안에 발생할 수 없다고 보고 재정의) |
| 시스템 전체 목표 | **1,000 TPS** (1,000 명 × 10 건 / 10 초) |
| 인스턴스당 측정 처리량 | **977 RPS** (1,000 TPS × 60 초 부하의 안정 구간, 실패 0%) → 가용률 70% 적용 시 **2 대** |
| 데이터 형식 | JSON, 10 개 필드 |
| 프로토콜 | HTTPS |
| 서버 사양 | vCPU 1, RAM 2 GB (Server A, B, C 각각) |

이 제약을 절대 잊지 말 것. 모든 설계 결정은 이 환경에서 동작 가능해야 함.

---

## 3. 5 가지 핵심 평가 항목과 매핑

| # | 항목 | 주 책임 서비스 | 핵심 기법 |
|---|------|--------|--------|
| ① | 대량 트래픽 + 동시성 제어 | Server A / Server C | A 진입 단순화 + per-request commit, C 트랜잭션 내 비관적 락 |
| ② | 분산 정합성 + 멱등성 | A→B→C 흐름 / Server C | Saga(choreography) + C 의 Outbox + `(user_id, coupon_type_id)` UNIQUE |
| ③ | 캐시 + Hot Spot 해결 | Server B | Redis 기반 발급 신청 적재 + Event 정보 캐시 (Cache-Aside) |
| ④ | Rate Limiting / Backpressure | Server B → Kafka 흐름 | Kafka consumer throttle (`max.poll.records` 등) + Resilience4j Circuit Breaker(A↔B) |
| ⑤ | 인프라 사이징 | 전체 | k6 부하 테스트 + Little's Law 기반 확장 계산 |

---

## 4. 시스템 아키텍처

```
[User] ──HTTPS──▶ [Server A] ──sync HTTP──▶ [Server B] ──Kafka(issue)──▶ [Server C]
                  진입/검증/요청 로그        Redis 적재/접수 응답         재고 차감/영구 저장
                       │                          │   ▲                       │
                    [MySQL-A]                  [Redis] │                   [MySQL-C]
                  issue_request               pending  │ Kafka(result)       event 마스터
                  (per-request commit)        user 신청 ◀─────────────────── coupon_type
                                              event cache                    coupon_type_inventory
                                              @Scheduled (10s)               user_coupon
                                                                             outbox_event
```

**데이터 흐름 (정상 케이스)**:
1. User → A: `POST /api/v1/coupons/issue-request` (헤더 `X-User-Id`)
2. A: 인증 (헤더 user_id) → `issue_request` per-request commit → B 호출 (sync, Resilience4j Circuit Breaker)
3. A → B (sync): Redis 에 (user_id, coupon_type_id) 중복 체크 → pending 상태로 적재 → Kafka 로 발급 신청 이벤트 publish → 즉시 200 "접수 완료" 응답
4. B → A: 즉시 결과 응답 ("접수 완료")
5. A → User: 즉시 결과 응답 ("접수 완료")
6. C 가 Kafka consume: 1 트랜잭션 안에서 — 유저 발급 내역 체크 → 이벤트 유효성 → `coupon_type_inventory` 비관적 락 차감 → `user_coupon` INSERT → `outbox_event` INSERT → 커밋
7. C 의 Outbox poller: `outbox_event` → Kafka result topic publish
8. B 가 result topic consume: Redis 의 user 발급 신청 상태 갱신
9. B 의 `@Scheduled`: Redis 에 10 초 이상 pending 인 신청 → C 의 internal GET API 직접 조회 → 결과를 Redis 에 반영 (보완)
10. User: GET API 또는 폴링으로 발급 결과 확인

**A→B 는 동기 (단 응답은 "접수 완료"), B↔C 는 양방향 비동기 Kafka** 가 핵심 설계.

---

## 5. 서비스별 책임

### 5.1 Server A — 진입점 (Gateway)

**책임**: "빠르게 받고, 잘못된 건 거르고, B 로 위임 + 요청 로그"

- API
    - `POST /api/v1/coupons/issue-request` — 발급 요청 접수
    - `GET /api/v1/events/{id}` — 이벤트 정보 조회 (B 의 캐시 API 를 프록시)
- 핵심 로직
    - 사용자 인증/인가: 단순 헤더 기반 `X-User-Id` (JWT 등 상위 인증 시스템 가정)
    - **요청 로그를 RDBMS 에 per-request commit** (감사/추적용)
    - Server B 호출 (sync, Resilience4j Circuit Breaker 로 보호)
- 절대 하지 말 것
    - A 에서 재고 관리하지 마라. 재고는 C 의 권한.
    - A 에서 무거운 트랜잭션 잡지 마라. A 는 진입점이다.
    - A→B 호출에 timeout/circuit breaker 없이 가지 마라.

### 5.2 Server B — 신청 접수 + 결과 캐시 (Decision / Cache)

**책임**: "신청 적재 → 즉시 접수 응답 → Kafka 로 위임 → 결과 캐시"

- API (내부 호출용 + 사용자용)
    - `POST /internal/v1/coupons/issue` — A 가 호출. 신청 접수
    - `GET /api/v1/events/{id}` — 이벤트 정보 조회 (Cache-Aside)
- 핵심 로직
    - **Redis 에 (user_id, coupon_type_id) 중복 체크 + pending 상태로 적재**
    - **Kafka producer 로 발급 신청 이벤트 publish** (acks=all, enable.idempotence=true, retries)
    - **Kafka consumer 로 발급 결과 수신** (C 가 publish 한 result) → Redis 의 신청 상태 갱신
    - Consumer throttle: `max.poll.records=10~50`, `concurrency=1~2` 로 1 vCPU 보호
    - **`@Scheduled` (1 초 주기)**: Redis 의 pending 중 10 초 초과 신청 → C 의 internal GET API 호출 → 결과를 Redis 에 반영 (Kafka 메시지 유실/지연 보완)
    - **Event 정보 캐시** (Cache-Aside, TTL): GET /events/:id 호출 시
- 절대 하지 말 것
    - 재고를 B 에서 관리하지 마라. 재고는 C 의 MySQL.
    - Redis + Kafka 가 원자적이라고 가정하지 마라. Kafka publish 실패는 스케줄러로 보완.
    - Consumer 에서 동기 외부 호출 길게 잡지 마라. lag 폭증.

### 5.3 Server C — 영구 저장 + 재고 권위 (Persistence / Source of Truth)

**책임**: "최종 진실의 보관자 + 재고 차감 권위"

- API
    - `POST /api/v1/coupons/{code}/redeem` — 쿠폰 사용
    - `GET /internal/v1/users/{userId}/coupons/{couponTypeId}` — B 의 스케줄러 보완용 internal 조회
    - (Kafka consumer): B 에서 발행한 발급 신청 이벤트 처리
    - (Kafka producer via Outbox poller): 결과 이벤트 publish
- 핵심 로직 — 발급 처리 (Kafka consumer)
    1 트랜잭션 안에서:
    - `user_coupon` 에 (user_id, coupon_type_id) 존재 여부 체크 (UNIQUE 위반 시 멱등 처리)
    - `event` 유효성 체크 (started_at <= now <= ended_at)
    - `coupon_type_inventory` 비관적 락 (`SELECT ... FOR UPDATE`) → 재고 차감
    - `user_coupon` INSERT
    - `outbox_event` INSERT (결과 publish 용)
    - 커밋
    - **Kafka publish 는 트랜잭션 밖** — Outbox poller 가 별도 처리
- 핵심 로직 — Redeem
    - `user_coupon.code` 로 조회 → `@Version` 낙관적 락으로 status/used_at 갱신
- 멱등성 보장
    - `(user_id, coupon_type_id)` UNIQUE → 중복 Kafka 메시지 차단
    - `coupon.code` UNIQUE → 발급 코드 유일성
- 절대 하지 말 것
    - UNIQUE constraint 없이 Kafka 메시지 처리하지 마라. 재시도 시 중복 발급.
    - `@Transactional` 안에서 Kafka publish 하지 마라. Outbox 로 분리.
    - Kafka consumer 에서 동기 외부 호출 하지 마라.

---

## 6. 핵심 기술 결정 (ADR)

### ADR-001: A→B 동기 호출, 단 즉시 "접수 완료" 응답
- 결정: A→B 는 sync HTTP 호출이지만, B 는 Redis 적재 + Kafka publish 후 즉시 200 "접수 완료" 응답
- 근거: 사용자 응답 latency 와 실제 발급 처리(C 의 비관적 락) 를 분리. B 는 진입 흡수기.
- 트레이드오프: 사용자가 "발급 성공/매진" 을 즉시 알 수 없음 → 폴링 또는 내정보 조회. UX 는 처음부터 "접수 완료 → 결과 확인" 모델.

### ADR-002: Saga (Choreography) + Outbox 패턴 (C 에 위치)
- 결정: 분산 트랜잭션은 choreography 기반 saga. C 가 발급 처리 후 `outbox_event` 테이블에 결과 이벤트 적재 → poller 가 Kafka publish → B 가 consume
- 근거: orchestrator 를 따로 두면 1 vCPU 에 부담. choreography 가 경량.
- 보상: redeem 단계에서는 사용자 재시도. 발급 단계에서는 (user_id, coupon_type_id) UNIQUE 가 중복 방어.

### ADR-003: 재고 관리는 Server C 의 MySQL + 비관적 락
- 결정: 재고는 `coupon_type_inventory` 테이블의 row 로 관리. `SELECT ... FOR UPDATE` 로 행 락.
- 근거: 발급은 비동기 처리(C 가 Kafka consumer) 이므로 사용자 응답 latency 와 락 경합이 분리. consumer concurrency 를 1~2 로 두면 1 vCPU 에서도 lock queue 폭발 회피.
- 트레이드오프: 핫 이벤트의 단일 row 에 락 경합 시 처리량 한계. README 에 측정 결과 + 확장 전략 명시.

### ADR-004: 멱등성은 (user_id, coupon_type_id) UNIQUE 로 보장
- 결정: 클라이언트 헤더의 Idempotency-Key 는 사용하지 않음. 1 인 1 장 제약 자체가 멱등성을 만족.
- 적용:
    - C 의 `user_coupon (user_id, coupon_type_id)` UNIQUE
    - B 가 Kafka 두 번 publish 해도 C 의 UNIQUE 가 차단
- redeem 의 멱등성: `user_coupon.status` + `@Version` 으로 처리 (이미 사용된 쿠폰은 재사용 불가)

### ADR-005: A 의 사용자별 Rate Limit 제거
- 결정: A 에 Bucket4j / 사용자별 토큰 버킷 없음. 1 인 1 장 제약이 자연 차단.
- Backpressure: Server B → C Kafka 흐름에서 consumer throttle (`max.poll.records`, `concurrency`) 로 시스템 보호. A↔B 회복성은 Resilience4j Circuit Breaker.

### ADR-006: 데이터베이스는 서비스별 분리 (Database per Service)
- 결정: Server A 의 MySQL (issue_request), Server B 의 Redis (pending/event cache), Server C 의 MySQL (event/coupon_type/inventory/user_coupon/outbox) 각자 다른 schema.
- 근거: 서비스 독립 배포, 결합도 감소.
- 비고: docker-compose 도 서비스별 컨테이너 분리 (`promotion-mysql-a` 호스트 3306 / `promotion-mysql-c` 호스트 3307). 각 컨테이너는 단일 schema 만 보유.

### ADR-007: 쿠폰 사용(Redeem) 은 낙관적 락
- 결정: `user_coupon` 의 `@Version` 컬럼으로 낙관적 락. 실패 시 클라이언트에 재시도 안내.
- 근거: 한 쿠폰을 동시에 사용하려는 시도는 발급보다 훨씬 적음. 비관적 락은 오버헤드.

### ADR-008: B 의 publish 보장 — producer 재시도 + 스케줄러 재발행 (횟수 cap)
- 결정: B 가 Kafka publish 시 Spring Kafka producer 의 `acks=all` + `enable.idempotence=true` + `retries` 로 일시 장애 흡수. 그래도 실패한 신청은 Redis 에 PENDING 상태로 남고, B 의 `@Scheduled` 가 cutoff(10s) 초과 PENDING 을 다음 순서로 처리한다:
    1. C 의 internal GET 으로 결과 조회 → 발견되면 Redis 결과 동기화 (Mode B/C 회복)
    2. C 가 모르고 `publishAttempts < max(=3)` 면 Kafka **재발행** + `HINCRBY publishAttempts` + `lastPublishedAt` 으로 ZSET score 갱신 (다음 cycle 까지 cutoff 만큼 grace)
    3. `publishAttempts ≥ max` 에 도달하면 FAILED 마감 + Micrometer counter `pending.scheduler.give_up` 증가
- 근거: Redis 적재와 Kafka publish 가 atomic 이 아니라 publish 자체가 유실되는 Mode A 가 존재. 단순 조회만 하던 이전 설계는 Mode A 에서 at-least-once 를 만족하지 못했다 — "Redis 에 PENDING 이 있다 = 처리 의도가 커밋됐다" 를 진실로 보고 재발행으로 회복.
- SLA: 카운터를 publish 시도 직전에 INCR 하므로 영구 publish 장애에서도 cutoff(10s) × max(3) = **30s 안에 결론**이 보장됨 (FAILED 또는 SUCCESS).
- 안전성: C 의 `(user_id, coupon_type_id)` UNIQUE + outbox 멱등 처리(중복 메시지는 early return) 가 이중 발급을 차단. 결과 메시지는 원본/재발행 중 먼저 처리된 메시지 한 건에서만 emit.
- 트레이드오프: C 가 영구 장애일 때 max 까지 시도 후 FAILED 종결. 이후 C 가 복구되어 늦게 도착한 메시지로 결과가 들어오면 result consumer 가 hash 를 SUCCESS 로 덮어씀 (Redis hash 유지, ZSET 은 비어있어 스케줄러 재진입 없음).
- Accept 단계 publish 실패: `CouponIssueAcceptService` 는 publish 실패를 swallow → 항상 ACCEPTED 응답. 5xx → DUPLICATE 흐름의 UX 모호성을 제거하고 회복은 전적으로 스케줄러에 위임.
- 전제: **단일 server-b 인스턴스**. 다중 인스턴스에서는 ZRANGEBYSCORE 가 동일 항목을 동시에 잡을 수 있어 cap 의 의미가 약해진다 (UNIQUE 가 정합성은 보호). 다중 인스턴스가 필요하면 별도 leader election 또는 ZPOPMIN 기반 work-stealing 도입.
- 배포 호환: 배포 직전 in-flight 항목은 publishAttempts 필드가 없어 fallback=1 로 해석 → 신규 항목 대비 재시도 한도가 한 회 줄어들 수 있음. UNIQUE 가 안전성 보호.
- 미적용: B 자체에 RDBMS Outbox 도입은 1 vCPU 자원 + ADR-006 (B = Redis only) 와 충돌하므로 보류.
- 스케줄러 전용 publish timeout: 기본 흐름의 3s 와 분리해 `app.kafka.scheduler-send-timeout-ms` (기본 500ms). cycle (`fixed-delay` 1s) × batch(50) 의 cumulative latency 폭주 방지.

### ADR-009: B↔C 양방향 Kafka (issue 토픽 + result 토픽)
- 결정: B → C 는 발급 신청 이벤트 (`coupon-issue-request`), C → B 는 발급 결과 이벤트 (`coupon-issue-result`).
- 근거: 사용자가 결과를 폴링할 때 B 의 Redis 만 보면 되도록 결과를 B 에 캐시. C 직접 조회는 1 vCPU MySQL-C 부담.
- Throttle: B 의 result consumer 는 `max.poll.records=10~50`, `concurrency=1~2` 로 1 vCPU 보호.

### ADR-010: A 의 요청 로그는 per-request commit (batch insert 아님)
- 결정: `IssueRequest` 를 요청당 한 번 JPA save() / commit. batch insert 큐 없음.
- 근거: 신규 흐름은 응답 latency 가 즉시 "접수 완료" 라 짧음 → A 에 별도 비동기 큐를 둘 필요 없음. 단순함이 우선.
- 측정 (2026-08-27): 1,000 TPS 부하에서 **MySQL-A 는 병목이 아니다** — CPU 27~39% 이고 커밋 fsync 를 꺼도
  처리량과 pool 대기가 그대로였다. 제약은 server-a 의 CPU 다. batch 회귀 검토는 불필요.

### ADR-011: 매진 신호의 negative cache (A 단락 + C 쓰기)
- 결정: `coupon:available:{eventId}:{couponTypeId}` 키 — 존재만으로 SOLD_OUT 표현 (값은 의미 없음). **A 의 `IssueRequestService` 진입부**에서 `EXISTS` 로 단락하고 (B 호출 자체를 skip + `IssueAcceptanceStatus.SOLD_OUT` 응답 + audit log 는 SOLD_OUT 으로 기록). C 의 `CouponIssueProcessor` 가 트랜잭션 `afterCommit` hook 에서 inventory 를 fresh read 해 `availableCount == 0` 이면 `SET EX(24h)`.
- 근거: 재고 권위는 여전히 C MySQL (ADR-003 유지). 본 캐시는 매진 후 후속 요청의 A→B HTTP + Redis savePending + Kafka 왕복 + C 비관적 락을 모두 제거하는 비용 절감 cache. **차단 위치를 A 진입으로 두면 B/C 자원이 매진 트래픽으로 낭비되지 않음** — 가장 일찍 단락. 평가 항목 ③ Hot Spot 에 정렬.
- afterCommit + fresh read 이유: 트랜잭션 안에서 결정하면 롤백 시 ghost write 가능 + 동시 다른 tx 의 최종 상태를 반영 못 함. 커밋 후 별도 read 로 "현재 권위 상태가 0" 임을 확인한 뒤에만 cache 적재.
- 트레이드오프:
    - Race 윈도우: cache SET 직전 통과한 요청은 정상 흐름으로 진입해 C 가 SOLD_OUT 처리. 사용자에게는 동일한 SOLD_OUT 결과 (latency 만 다름).
    - Cache miss/Redis blip: B 가 fall-through → 정상 흐름. degrade 하지 않음.
    - Admin restock: cache 가 stale FALSE 로 남으므로 admin 운영 시 키 수동 삭제 필요.
- 안전성: cache 는 권위 아님. stale 이 정합성을 깨지 않음. C 의 비관적 락 + UNIQUE 가 권위.

---

## 7. 기술 스택

| 카테고리 | 기술 | 버전 |
|------|------|------|
| 언어 | Java | 21 |
| 프레임워크 | Spring Boot | 3.5.14 |
| 빌드 | Gradle (Kotlin DSL) | 8.x |
| RDBMS | MySQL | 8.x |
| In-memory | Redis | 7.x |
| 메시지 큐 | Kafka | 3.x |
| ORM | Spring Data JPA + Hibernate | (Spring Boot 관리) |
| Migration | Flyway | (Spring Boot 관리) |
| Resilience | Resilience4j | latest |
| 부하 테스트 | k6 | latest |
| 컨테이너 | Docker, docker-compose | - |
| 모니터링 | Spring Actuator + Micrometer (Prometheus 노출) | - |

> Bucket4j 는 사용하지 않음 (ADR-005). Idempotency-Key 헤더 / 별도 캐시 라이브러리도 없음 (ADR-004).

---

## 8. 모듈 구조

```
promotion/
├── CLAUDE.md                        ← 본 문서
├── README.md                        ← 평가자용 진입 문서
├── settings.gradle.kts
├── build.gradle.kts                 ← 공통 의존성/플러그인
├── docker-compose.yml
│
├── common/                          ← 공통 DTO, 이벤트 페이로드
│   └── build.gradle.kts             ← java-library
│
├── server-a/                        ← 진입점 + 요청 로그
│   ├── build.gradle.kts             ← bootJar
│   ├── Dockerfile
│   └── src/main/...
│
├── server-b/                        ← Redis 적재 + Kafka 양방향 + 스케줄러 + Event 캐시
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/main/...                 ← MySQL 의존 없음 (Redis only)
│
├── server-c/                        ← 영구 저장 + 비관적 락 + Outbox + Redeem
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/main/...
│
├── load-test/
│   └── scenarios/
│
└── docs/
    ├── rivkode_system_design.md     ← 신규 설계 출처 (source of truth)
    ├── architecture/                ← 아키텍처 상세
    └── decisions/                   ← ADR 보충
```

`server-a/b/c` 는 각자 독립 `bootJar`. `common` 은 라이브러리.

---

## 9. 도메인 모델 스케치

### Server A (MySQL — schema `server_a`)
- `issue_request` (request_id PK, user_id, event_id, coupon_type_id, status, created_at)

### Server B (Redis only)
- `issue:pending:{user_id}:{coupon_type_id}` — Hash (status / created_at / event_id / request_id / publishAttempts / lastPublishedAt)
- `issue:pending:zset` — Sorted Set (member = `user_id:coupon_type_id`, score = createdAt 또는 lastPublishedAt epoch ms) — 스케줄러 ZRANGEBYSCORE 용
- `event:{event_id}` — Hash (cache, TTL)
- `coupon:available:{event_id}:{coupon_type_id}` — ADR-011 SOLD_OUT negative cache. 키 존재 = 매진. 값 의미 없음. TTL 24h. **C 가 쓰고 A 가 읽음** (A 진입에서 단락).

### Server C (MySQL — schema `server_c`)
- `event` (event_id PK, name, content, started_at, ended_at)
- `coupon_type` (coupon_type_id PK, event_id FK, name, discount_rate)
- `coupon` (coupon_id PK, coupon_type_id FK, name) — 마스터 (선택, 단순화 시 생략 가능)
- `coupon_type_inventory` (coupon_type_inventory_id PK, event_id, coupon_type_id, total_inventory, available_count)
- `user_coupon` (user_coupon_id PK, code UNIQUE, user_id, event_id, coupon_type_id, status, version, issued_at, used_at NULLABLE, **UNIQUE (user_id, coupon_type_id)**)
- `outbox_event` (outbox_event_id PK, aggregate_id, event_type, payload, status, created_at, published_at NULLABLE)

### Kafka 토픽
- `coupon-issue-request` — B → C 발급 신청 이벤트
- `coupon-issue-result` — C → B 발급 결과 이벤트 (성공/실패/매진)

---

## 10. 절대 하지 말 것 (Anti-patterns)

- ❌ A 에서 RDBMS 트랜잭션을 길게 잡기 (특히 외부 호출 포함)
- ❌ 재고를 Redis 로 관리 (신규 설계는 C 의 MySQL 비관적 락이 권위 — Redis 는 캐시일 뿐. 단 SOLD_OUT 신호 negative cache 는 ADR-011 에 따라 허용 — 권위 아닌 비용 절감 목적)
- ❌ A→B 호출에 timeout/circuit breaker 없음
- ❌ Kafka 메시지 처리 시 멱등성 보장 안 함 (UNIQUE constraint 누락)
- ❌ `@Transactional` 안에서 외부 API/Kafka 호출 (트랜잭션 길어짐 — Outbox 로 분리)
- ❌ N+1 쿼리 (특히 쿠폰 조회 API)
- ❌ Lombok `@Data` 남용 (equals/hashCode 의도치 않은 동작 위험)
- ❌ `findAll()` 후 메모리에서 필터링
- ❌ 헥사고날 아키텍처 강제 (가벼운 레이어드 + DDD 로 충분)
- ❌ 모든 서비스가 같은 DB 공유 (서비스 분리 원칙 위배)
- ❌ B → C 의 Kafka consumer 가 throttle 없이 무제한 poll (1 vCPU MySQL-C 의 비관적 락 처리량 초과 위험)
- ❌ 부하 테스트 없이 "이 정도면 될 거 같다" 는 추정으로 사이징 보고

---

## 11. 코딩 컨벤션

- 패키지 구조: 멀티 모듈 `{servera|serverb|serverc}`
- 레이어드 아키텍처 + 가벼운 DDD (Aggregate 개념까지만)
- DTO 와 도메인 모델 분리, 도메인 객체와 JPA 엔티티 분리 (`xxxJpaEntity`)
- 예외: 비즈니스 예외(`BusinessException`) 와 시스템 예외 구분, `@RestControllerAdvice` 로 일괄 처리
- 로깅: SLF4J + Logback, 요청별 traceId (MDC) 부여
- 테스트: 핵심 동시성/멱등성 로직은 통합 테스트 (Testcontainers 또는 docker-compose 전제)
- 주석: 비즈니스 결정/트레이드오프는 코드 주석에 짧게라도 남길 것

---

## 12. Claude Code 작업 시 주의사항

- 새 코드 작성 전, 항상 본 문서의 ADR 과 Anti-pattern 을 먼저 확인할 것
- 설계 결정과 충돌이 의심되면 코드 작성 전에 사용자에게 질문할 것
- 새 의존성 추가 시 본 문서의 기술 스택 표를 함께 업데이트
- ADR 이 추가/변경될 때마다 본 문서의 §6 에 반영
- 커밋 메시지는 명확하게 (예: `feat(server-c): pessimistic-lock inventory decrement`)
- 테스트 파일은 핵심 로직(동시성, 멱등성) 에 집중. 100 % 커버리지 목표 X.

---

## 13. 참고 자료

- Microservices.io — Saga, Outbox, Idempotent Consumer 패턴
- Resilience4j 공식 문서
- k6 공식 문서
- Little's Law (인프라 사이징): `L = λW`
