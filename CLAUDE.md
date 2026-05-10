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
    - 이벤트 100 개 × 이벤트별 쿠폰 10,000 장
    - 사용자는 이벤트당 1 장만 발급 가능 (`(user_id, coupon_type_id)` UNIQUE)
    - 발급(Issue): 사용자가 쿠폰을 청구하는 행위 (메인 트래픽)
    - 사용(Redeem): 발급받은 쿠폰을 결제 등에서 소비하는 행위 (보조 기능)
- **응답 모델**: 사용자는 발급 요청 시 **"접수 완료"** 만 즉시 응답으로 받고, 실제 발급 결과는 폴링/내정보 조회로 비동기 확인 (이벤트 기반 일관성)

---

## 2. 트래픽 시나리오 및 자원 제약

| 항목 | 값 |
|------|------|
| 총 사용자 (피크) | 1,000 명 |
| 사용자당 요청 | 10 초 내 100 건 |
| 인스턴스당 목표 | **1,000 TPS** (Server A 한 대 기준) |
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

### ADR-008: B 의 Kafka publish 실패는 producer 재시도 + 스케줄러 보완
- 결정: B 가 Kafka publish 시 Spring Kafka producer 의 `acks=all` + `enable.idempotence=true` + `retries` 로 일시 장애 흡수. 그래도 실패하면 Redis 의 pending 상태로 남고, B 의 `@Scheduled` 가 10 초 후 C 의 internal GET API 로 직접 조회하여 보완.
- 근거: Redis + Kafka 는 원자적이지 않음. Outbox 를 B 에 두면 RDBMS 가 추가로 필요해 책임이 커짐. 1 인 1 장 UNIQUE 가 중복 publish 를 방어하므로 직접 publish + 스케줄러 보완이 충분.

### ADR-009: B↔C 양방향 Kafka (issue 토픽 + result 토픽)
- 결정: B → C 는 발급 신청 이벤트 (`coupon-issue-request`), C → B 는 발급 결과 이벤트 (`coupon-issue-result`).
- 근거: 사용자가 결과를 폴링할 때 B 의 Redis 만 보면 되도록 결과를 B 에 캐시. C 직접 조회는 1 vCPU MySQL-C 부담.
- Throttle: B 의 result consumer 는 `max.poll.records=10~50`, `concurrency=1~2` 로 1 vCPU 보호.

### ADR-010: A 의 요청 로그는 per-request commit (batch insert 아님)
- 결정: `IssueRequest` 를 요청당 한 번 JPA save() / commit. batch insert 큐 없음.
- 근거: 신규 흐름은 응답 latency 가 즉시 "접수 완료" 라 짧음 → A 에 별도 비동기 큐를 둘 필요 없음. 단순함이 우선.
- 측정: 1 vCPU MySQL-A 가 1000 commit/sec 를 처리 못 하면 batch 로 회귀 검토.

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
- `issue:pending:{user_id}:{coupon_type_id}` — Hash (status / created_at / event_id / request_id)
- `issue:pending:zset` — Sorted Set (member = `user_id:coupon_type_id`, score = created_at epoch ms) — 스케줄러 ZRANGEBYSCORE 용
- `event:{event_id}` — Hash (cache, TTL)

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
- ❌ 재고를 Redis 로 관리 (신규 설계는 C 의 MySQL 비관적 락이 권위 — Redis 는 캐시일 뿐)
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
