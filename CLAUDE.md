# 프로모션 시스템

> 이 문서는 Claude Code가 매 세션마다 자동으로 읽는 프로젝트 컨텍스트입니다.
> 새 작업을 시작하기 전에 이 문서의 결정사항을 반드시 준수해 주세요.
> 결정사항을 변경해야 한다고 판단되면, 먼저 사용자와 상의하세요.

---

먼저 다음을 순서대로 수행해줘:

1. `CLAUDE.md`를 read tool로 정독 (프로젝트 컨텍스트)
2. `docs/prompts/day1-kickoff.md`를 read tool로 정독 (오늘 작업 지시)

두 파일을 모두 읽은 후, day1-kickoff.md의 Phase 0부터 시작해줘.

중요한 규칙:
- 각 Phase 종료 시 반드시 멈추고 결과 보고 후 내 확인을 기다릴 것
- "다음 진행"이라는 명시적 신호 없이 다음 Phase로 넘어가지 말 것
- 의문점이나 결정이 필요한 사항이 있으면 작업 시작 전에 질문할 것

## 1. 프로젝트 개요

- **목적**: 백엔드 채용 과제 (프로모션 도메인)
- **기간**: 5일
- **핵심 평가 포인트**: 제한된 자원(1 vCPU, 2GB RAM)에서 대량 트래픽을 안정적으로 처리하는 분산 시스템 설계 및 구현
- **시나리오**: **선착순 쿠폰 발급 및 사용 시스템**
    - 가정: "콘서트 사전 예매 할인 쿠폰 - 선착순 10,000장"
    - 발급(Issue): 사용자가 쿠폰을 청구하는 행위 (메인 트래픽)
    - 사용(Redeem): 발급받은 쿠폰을 결제 등에서 소비하는 행위 (보조 기능)

---

## 2. 트래픽 시나리오 및 자원 제약

| 항목 | 값 |
|------|------|
| 총 사용자 | 1,000명 |
| 사용자당 요청 | 10초 내 100건 |
| 전체 부하 | 평균 10,000 TPS |
| 인스턴스당 목표 | 500 ~ 1,000 TPS |
| 데이터 형식 | JSON, 10개 필드 |
| 프로토콜 | HTTPS |
| 서버 사양 | vCPU 1, RAM 2GB (Server A, B, C 각각) |

이 제약을 절대 잊지 말 것. 모든 설계 결정은 이 환경에서 동작 가능해야 함.

---

## 3. 5가지 핵심 평가 항목과 매핑

| # | 항목 | 주 책임 서비스 | 핵심 기법 |
|---|------|--------|--------|
| ① | 대량 트래픽 + 동시성 제어 | Server A | HikariCP 튜닝, 비동기 로깅, batch insert |
| ② | 분산 정합성 + 멱등성 | A→B→C 흐름 / Server C | Saga(choreography) + Outbox + Idempotency-Key |
| ③ | 캐시 + Hot Spot 해결 | Server B | Redis DECR, key sharding, request coalescing, 2단 캐시 |
| ④ | Rate Limiting / Backpressure | Server A | Bucket4j (Redis-backed), Circuit Breaker(Resilience4j) |
| ⑤ | 인프라 사이징 | 전체 | k6 부하 테스트 + Little's Law 기반 확장 계산 |

각 항목은 README.md의 해당 섹션과 코드의 해당 모듈에서 명확히 다뤄져야 한다.

---

## 4. 시스템 아키텍처

```
                        ┌────────────────────────────────────────────────┐
                        │              Internal Network                  │
                        │                                                │
[User] ──HTTPS──▶ [Server A] ──sync──▶ [Server B] ──async(Kafka)──▶ [Server C]
                  진입/검증/통제          재고/캐시           영구 저장/멱등성
                       │                     │                       │
                    [MySQL-A]            [Redis]                 [MySQL-C]
                  요청 로그/감사         재고 카운터               쿠폰 마스터
                                       Outbox(보조)              redemption 기록
```

**데이터 흐름 (정상 케이스)**:
1. User → A: `POST /coupons/issue-requests` + `Idempotency-Key` 헤더
2. A: 인증 → Rate Limit 검사 → Idempotency 검사 → 요청 로그 적재 → B 호출
3. A → B (sync, timeout 200ms): Redis DECR로 재고 차감 → 쿠폰 코드 생성 → Outbox에 적재
4. B → A: 즉시 결과 응답 (성공 or 매진)
5. A → User: 결과 응답
6. B → C (async, Kafka via Outbox poller): 쿠폰 영구 저장 (멱등성 보장)

**A→B는 동기, B→C는 비동기**인 것이 핵심 설계.

---

## 5. 서비스별 책임

### 5.1 Server A — 진입점 (Gateway / Validator)

**책임**: "빠르게 받고, 잘못된 건 거르고, B로 위임"

- API
    - `POST /api/v1/coupons/issue-requests` — 발급 요청 접수
    - `GET /api/v1/coupons/issue-requests/{requestId}` — 요청 상태 조회 (선택)
- 핵심 로직
    - 사용자 인증/인가 (JWT 등 — 5일 일정 고려해 단순 헤더 기반 user_id로 갈 수 있음) user_id 로 우선 진행
    - **Rate Limiter**: 사용자별 N req/sec 제한 (Bucket4j + Redis)
    - **Idempotency-Key 검사**: 같은 키로 중복 요청 시 캐시된 결과 반환
    - 요청 로그를 RDBMS에 기록 (감사/추적용, **비동기 또는 batch로**)
    - Server B 호출 (sync, Resilience4j Circuit Breaker로 보호)
- 절대 하지 말 것
    - A에서 재고 관리하지 마라. 재고는 B의 Redis가 권한을 가짐.
    - A에서 무거운 트랜잭션 잡지 마라. A는 진입점이다.
    - A→B 호출에 timeout/circuit breaker 없이 가지 마라.

### 5.2 Server B — 재고 관리 (Cache / Decision)

**책임**: "한정 자원의 분배 결정자"

- API (내부 호출용)
    - `POST /internal/v1/coupons/issue` — 재고 차감 + 쿠폰 코드 생성
- 핵심 로직
    - **Redis atomic 연산으로 재고 차감** (DECR or Lua script — Lua 권장: 음수 방지 + 쿠폰 코드 생성을 atomically)
    - 재고 있으면 쿠폰 코드 발급, 없으면 즉시 매진 응답
    - **Outbox 테이블에 발급 이벤트 기록** (NoSQL이지만 Outbox는 정합성을 위해 일관성 있는 저장소 필요 — Redis Streams 또는 보조 RDBMS 고려) 보조 RDBMS 로 진행
    - Outbox poller가 Kafka로 발행
- Hot Spot 대응
    - 단일 키 (`event:{id}:stock`)에 트래픽이 몰리는 문제
    - 대응: **stock sharding** — 재고 10,000장을 10개 샤드로 분할(`event:{id}:stock:{0..9}`), 사용자가 hash로 라우팅
    - 보조: probabilistic early refresh, request coalescing
- 절대 하지 말 것
    - 재고를 RDBMS row lock으로 관리하지 마라. 1 vCPU에서 즉사한다.
    - Redis GET 후 SET 패턴 쓰지 마라. atomic이 아니다. 반드시 DECR 또는 Lua.
    - 동기적으로 C까지 호출하지 마라. C는 비동기.

### 5.3 Server C — 영구 저장 (Persistence / Source of Truth)

**책임**: "최종 진실의 보관자"

- API
    - `POST /api/v1/coupons/{code}/redeem` — 쿠폰 사용
    - `GET /api/v1/users/{userId}/coupons` — 내 쿠폰 조회
    - (Kafka consumer): B에서 발행한 발급 이벤트 처리
- 핵심 로직
    - Kafka consumer가 발급 이벤트 수신 → MySQL에 영구 저장
    - **멱등성 보장**: `(idempotency_key)` 또는 `(user_id, event_id)`에 UNIQUE constraint. 중복 이벤트는 DB가 거부.
    - 쿠폰 사용(redeem)은 트랜잭션 안에서 `used_at` 체크 후 update
- 락 전략 (redeem)
    - **낙관적 락 권장**: `@Version` 필드로 version 체크. 실패 시 사용자에게 재시도 안내.
    - 비관적 락은 트래픽 급증 시 락 경합으로 성능 저하 가능. README에 트레이드오프 명시.
- 절대 하지 말 것
    - UNIQUE constraint 없이 Kafka 메시지 처리하지 마라. 재시도 시 중복 발급 발생.
    - Kafka consumer에서 동기 외부 호출 하지 마라. consumer lag 폭증.

---

## 6. 핵심 기술 결정 (ADR 요약)

### ADR-001: A→B 동기, B→C 비동기 (하이브리드)
- 결정: A→B는 sync (재고 차감 결과를 사용자에게 즉시 응답 필요), B→C는 async (영구 저장은 사용자 응답 latency와 분리 가능)
- 근거: 선착순 UX는 즉시 결과를 요구. 비동기 + 폴링은 사용자가 F5 연타하게 만들어 트래픽이 더 늘어남.
- 트레이드오프: A의 스레드가 B 응답까지 묶임 → Resilience4j Circuit Breaker + 짧은 timeout(200ms)로 보완

### ADR-002: Saga (Choreography) + Outbox 패턴
- 결정: 분산 트랜잭션은 choreography 기반 saga로 처리. B가 발급 후 outbox에 이벤트 적재 → poller가 Kafka 발행 → C가 consume
- 근거: orchestrator를 따로 두면 1 vCPU에 부담. choreography가 경량.
- 보상 트랜잭션: C 저장 실패가 N회 반복되면 dead letter queue → 알림 → B에서 재고 복구 이벤트 발행
- **대안**: CDC (Debezium) 기반 outbox 발행도 강력하지만 5일 일정상 poller 방식으로 구현. README에 "프로덕션 진화 방향으로 CDC" 명시.

### ADR-003: 재고 관리는 Redis (Lua script)
- 결정: 재고 카운터는 Redis. atomic 연산(Lua script)으로 race condition 차단.
- 금지: RDBMS row lock 기반 재고 관리. 1 vCPU에서 lock 경합으로 처리량이 수십 TPS로 떨어짐.

### ADR-004: Idempotency-Key 헤더 기반 멱등성
- 결정: 클라이언트가 `Idempotency-Key` 헤더로 UUID 전달. A에서 1차 검사(Redis 캐시), C의 DB UNIQUE constraint로 최종 보장.
- 적용 범위: 발급(issue), 사용(redeem) 양쪽 모두

### ADR-005: Rate Limiting은 Server A에서
- 결정: Bucket4j + Redis backend로 사용자별 토큰 버킷.
- 정책: 사용자당 10 req/sec (10초에 100건 시나리오에 맞춤). 초과 시 429 Too Many Requests 즉시 반환.
- 백프레셔: A의 큐가 가득 차면 503 반환 (시스템 보호 우선)

### ADR-006: 데이터베이스는 서비스별 분리 (Database per Service)
- 결정: Server A의 MySQL, Server B의 Redis, Server C의 MySQL은 각자 다른 인스턴스 (또는 다른 schema).
- 근거: 서비스 독립 배포 요구사항 충족, 결합도 감소
- 비고: 로컬 개발에서는 docker-compose로 단일 MySQL에 다른 schema로 운영 가능

### ADR-007: 쿠폰 사용(Redeem)은 낙관적 락
- 결정: `@Version` 컬럼으로 낙관적 락. 실패 시 클라이언트에 재시도 안내.
- 근거: 한 쿠폰을 동시에 사용하려는 시도는 흔치 않음. 비관적 락은 오버헤드.

---

## 7. 기술 스택

| 카테고리 | 기술 | 버전 |
|------|------|------|
| 언어 | Java | 21 |
| 프레임워크 | Spring Boot | 3.5.14 (Java 21 호환 GA) |
| 빌드 | Gradle (Kotlin DSL) | 8.x |
| RDBMS | MySQL | 8.x |
| In-memory / NoSQL | Redis | 7.x |
| 메시지 큐 | Kafka | 3.x |
| ORM | Spring Data JPA + Hibernate | (Spring Boot 관리) |
| Migration | Flyway | (Spring Boot 관리) |
| Rate Limiter | Bucket4j | latest |
| Resilience | Resilience4j | latest |
| 부하 테스트 | k6 | latest |
| 컨테이너 | Docker, docker-compose | - |
| 모니터링 | Spring Actuator + Micrometer (Prometheus 노출) | - |

`server-c`의 redemption은 JPA + 낙관적 락. `server-a`의 요청 로그는 batch insert를 위해 JdbcTemplate 직접 사용 고려.

---

## 8. 모듈 구조

```
promotion/
├── CLAUDE.md                        ← 본 문서
├── README.md                        ← 제출용 (Day 5)
├── settings.gradle.kts
├── build.gradle.kts                 ← 공통 의존성/플러그인
├── docker-compose.yml
│
├── common/                          ← 공통 DTO, 예외, 유틸
│   └── build.gradle.kts             ← java-library 플러그인
│
├── server-a/                        ← 진입점
│   ├── build.gradle.kts             ← spring-boot bootJar 활성화
│   ├── Dockerfile
│   └── src/main/...
│
├── server-b/                        ← 재고 관리
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/main/...
│
├── server-c/                        ← 영구 저장
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/main/...
│
├── load-test/
│   └── scenarios/
│       ├── issue-burst.js
│       └── redeem-steady.js
│
└── docs/
    ├── architecture.md
    ├── decisions/
    └── load-test-report.md
```

**중요**: `server-a/b/c`는 각자 독립적으로 `bootJar` 생성 가능해야 하고, 각자 Dockerfile을 가짐. `common`은 라이브러리 모듈 (bootJar 비활성).

---

## 9. 도메인 모델 스케치

> Day 1 작업에서 이 스케치를 기반으로 구체화. 변경 시 본 문서도 함께 업데이트.

### Server A
- `IssueRequest` (request_id, user_id, event_id, idempotency_key, status, created_at)
- `Event` (event_id, name, total_stock, started_at, ended_at) — 마스터는 운영 도구가 입력 가정

### Server B (Redis)
- `event:{eventId}:stock:{shardId}` — INTEGER, 샤딩된 재고
- `coupon:code:{code}` — HASH, 임시 발급 정보 (TTL 후 정리)
- `outbox:pending` — LIST 또는 별도 RDBMS 테이블

### Server C
- `Coupon` (id, code UNIQUE, user_id, event_id, idempotency_key UNIQUE, issued_at, used_at NULLABLE, version)
- `OutboxOffset` (consumer 오프셋 추적, 선택)

---

## 10. 절대 하지 말 것 (Anti-patterns)

이 항목들은 평가에서 즉시 감점 요소:

- ❌ A에서 RDBMS 트랜잭션을 길게 잡기 (특히 외부 호출 포함)
- ❌ 재고를 RDBMS row lock(`SELECT FOR UPDATE`)으로 관리
- ❌ A→B 호출에 timeout/circuit breaker 없음
- ❌ Idempotency-Key 검사 없이 발급/사용 처리
- ❌ Kafka 메시지 처리 시 멱등성 보장 안 함 (UNIQUE constraint 누락)
- ❌ `@Transactional` 안에서 외부 API/Kafka 호출 (트랜잭션 길어짐)
- ❌ N+1 쿼리 (특히 쿠폰 조회 API)
- ❌ Lombok `@Data` 남용 (equals/hashCode 의도치 않은 동작 위험)
- ❌ `findAll()` 후 메모리에서 필터링
- ❌ 헥사고날 아키텍처는 금지 X
- ❌ 모든 서비스가 같은 DB 공유 (서비스 분리 원칙 위배)
- ❌ 단일 Redis 키에 모든 재고 (Hot Spot — sharding 필수)
- ❌ 부하 테스트 없이 "이 정도면 될 거 같다"는 추정으로 사이징 보고

---

## 11. 코딩 컨벤션

- 패키지 구조: 멀티 모듈 {servera|serverb|serverc}
- 레이어드 아키텍처 + 가벼운 DDD (Aggregate 개념까지만, 풀 DDD는 시간 부족)
- DTO와 도메인 모델 분리, 도메인 객체와 Jpa 객체 분리 예를 들어 entity 는 xxxJpaEntity 같이 표현
- 예외: 비즈니스 예외(`BusinessException`)와 시스템 예외 구분, `@RestControllerAdvice`로 일괄 처리
- 로깅: SLF4J + Logback, 요청별 traceId (MDC) 부여
- 테스트: 핵심 동시성 로직은 반드시 통합 테스트 (Testcontainers 활용 가능)
- 주석: 비즈니스 결정/트레이드오프는 코드 주석에 짧게라도 남길 것 (평가자가 코드 읽을 때 도움)

---

## 12. 5일 로드맵

| Day | 목표 | 산출물 |
|------|------|------|
| 1 | 셋업 + 설계 문서 | repo 구조, docker-compose, CLAUDE.md/README.md 초안, 도메인 모델 확정 |
| 2 | Server A + B 핵심 구현 | Rate Limiter, Idempotency, Redis Lua 재고 차감, A↔B sync 흐름 |
| 3 | Server C + Outbox/Saga | Outbox poller, Kafka producer/consumer, Redeem API, 멱등성 |
| 4 | 부하 테스트 + 튜닝 | k6 시나리오 실행, HikariCP/JVM 튜닝, 결과 보고서 초안 |
| 5 | README 최종 + 사이징 | 100,000명 처리 서버 수 계산, README 다듬기, 영상/스크린샷 |

각 Day 시작 시 이전 Day 산출물을 점검하고 가장 가치 있는 작업부터 진행할 것. 시간 부족 시 README의 "설명으로 대체" 활용.

---

## 13. Claude Code 작업 시 주의사항

- 새 코드 작성 전, 항상 이 문서의 ADR과 Anti-pattern을 먼저 확인할 것
- 설계 결정과 충돌이 의심되면 코드 작성 전에 사용자에게 질문할 것
- 5일 일정이므로 **완벽보다 동작 우선**. 단, 평가 항목 5가지 중 하나는 깊게 가야 함.
- 새 의존성 추가 시 본 문서의 기술 스택 표를 함께 업데이트
- ADR이 추가/변경될 때마다 본 문서의 Section 6에 반영
- 커밋 메시지는 명확하게 (예: `feat(server-a): add idempotency-key validation`)
- 테스트 파일은 핵심 로직(동시성, 멱등성)에 집중. 100% 커버리지 목표 X.

---

## 14. 참고 자료

- Microservices.io — Saga, Outbox, Idempotent Consumer 패턴
- Bucket4j 공식 문서
- Resilience4j 공식 문서
- k6 공식 문서
- Little's Law (인프라 사이징): `L = λW`

