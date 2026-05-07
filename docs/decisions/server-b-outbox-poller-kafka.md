# Server B Outbox poller + Kafka producer 설계 (ADR-002 구체화)

> **Status**: 결정 (2026-05-07)
> **결정**: `@Scheduled(fixedDelay=200ms)` poller + `SELECT … FOR UPDATE SKIP LOCKED` +
> producer `acks=all + enable.idempotence=true` + Server C UNIQUE constraint = 의미적 exactly-once
> **연관**: CLAUDE.md ADR-002, ADR-006, §3 평가 항목 ② (분산 정합성 + 멱등성)
>
> 본 문서는 PR #14 의 Outbox poller / Kafka producer 의 결정 근거. PR #15 의 Server C
> Kafka consumer 와 짝을 이룬다.

---

## 1. 문제

PR #8/#10 에서 `coupon_issue_outbox` 테이블에 발급 이벤트 INSERT 까지 구현됨. 이제
이 테이블의 `published=false` 행을 Kafka `coupon.issued` 토픽으로 발행하고
`published=true` 로 마킹하는 메커니즘이 필요. 5 가지 결정점:

1. Polling 전략 (스케줄러 vs worker thread)
2. 발행 보장 등급 (at-least-once vs exactly-once)
3. Topic / partition / key 설계
4. 동시성 (두 인스턴스 동시 실행 방어)
5. 테스트 전략

---

## 2. 결정

### 2.1 `@Scheduled(fixedDelay=200ms)` + SKIP LOCKED 안전망

- Spring 의 `@Scheduled` + `fixedDelayString="${app.outbox.poller.fixed-delay-ms:200}"`.
- `fixedDelay` (이전 cycle 종료 후 200ms) 가 lag 발생 시 자동 backpressure.
- 200ms = server-a → server-b sync timeout (200ms, ADR-001) 와 정합 — 사용자 응답 직후
  평균 1 cycle 안에 Kafka 발행 완료.
- worker thread 분리는 5일 일정에 과함. `@Scheduled` 도 Spring 의 단일 ScheduledExecutor
  위에서 도므로 Web request 스레드 풀과 격리.

### 2.2 at-least-once + Consumer 멱등성 = 의미적 exactly-once

- Producer config: `acks=all + enable.idempotence=true + max.in.flight=5 + retries=5`.
  → broker 측 retry dedup 까지 보장. 단, **end-to-end exactly-once 는 아님** —
  poller 가 markPublished 직전 크래시 시 다음 cycle 에서 재발행 가능.
- Server C 의 `(user_id, idempotency_key) UNIQUE` constraint 가 중복 INSERT 거부 →
  의미적 exactly-once. ADR-002 의 "B 는 at-least-once 발행, C 가 멱등 처리" 와 정합.
- Producer idempotence 만으로 exactly-once 라고 주장하지 않는다 — 그건 흔한 오해.
  Kafka transactional producer (read_committed isolation + transactional.id) 가
  정통 방법이지만, MySQL Outbox 와 트랜잭션 묶기에는 별도 작업 필요 (CDC 기반)
  — 본 과제 5일 일정 외.

### 2.3 Topic 설계: `coupon.issued`, partitions=3, RF=1, key=userId

- **Partitions=3**: 단일 broker 환경 + 향후 확장 시 여유. partition 수가 늘면 broker
  메타데이터 / leader 부하 증가 → 1 vCPU 환경에서 과한 partition 은 부담.
- **RF=1**: docker-compose 의 broker 1 대 (`KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1`)
  와 정합. 운영에선 RF≥2 권장 — 본 과제 외.
- **Key=userId**: 같은 사용자의 여러 이벤트(향후 redeem/cancel) 가 같은 partition →
  consumer 측 ordering 보장. eventId key 는 단일 이벤트(`event_id=1`)에 트래픽이 몰려
  partition skew 를 유발하므로 거부.
- **Value=Outbox 행의 payload JSON 그대로** (`CouponIssuedEventPayload` 형식, 평탄화).
  consumer 가 같은 record 로 deserialize.
- **Headers**: `idempotencyKey`, `couponCode` — consumer 디버깅 + 멱등 처리 시 빠른
  추적 (header 만 보고도 어느 쿠폰인지 식별 가능).

### 2.4 단일 인스턴스 운영 가정 + SKIP LOCKED 안전망 (트레이드오프)

- 본 과제 5일 일정에서 server-b 는 단일 인스턴스 운영. 그러나 코드는 **두 인스턴스
  동시 실행도 안전**하도록 SKIP LOCKED 사용.
- `findUnpublishedForUpdate(int limit)` 가 native query 로 `FOR UPDATE SKIP LOCKED`
  → 다른 트랜잭션이 잠근 행을 건너뜀.
- 흐름 분리 (안티패턴 회피, CLAUDE.md §10):
    1. **트랜잭션 1**: SKIP LOCKED 로 N 건 잡음 → 트랜잭션 종료 (lock 해제).
    2. **트랜잭션 밖**: Kafka publish (외부 호출은 트랜잭션 안에서 금지).
    3. **트랜잭션 2 (행별)**: markPublished + save.
- (1) → (3) 사이에 다른 poller 인스턴스가 같은 행을 다시 잡을 가능성은 있음 → 이 경우
  Kafka 로 같은 메시지가 두 번 발행됨. **Server C UNIQUE constraint 가 흡수** (의미적
  exactly-once).
- 더 강한 보장 (claim 패턴 / processing_owner 컬럼) 은 5일 일정에 과함.

### 2.5 테스트 전략

- 단위 테스트: `OutboxPoller` mock publisher/repository + `KafkaCouponIssuedEventPublisher`
  mock KafkaTemplate (CompletableFuture 직접 조작).
- 통합 테스트: `@SpringBootTest` + host port MySQL + `@MockitoBean
  CouponIssuedEventPublisher`. Repository SKIP LOCKED native query + 트랜잭션 경계 +
  markPublished 흐름 검증. Kafka 통합은 PR #17 (k6 day3 시나리오) 의 책임.
- IT 부팅 시 자동 cycle 비활성화: `app.outbox.poller.initial-delay-ms=3600000`
  → poll() 메서드를 직접 호출.

---

## 3. 거부된 대안

### A. Worker thread + ExecutorService 직접 관리

- 장점: schedule 세밀 제어, 별도 thread pool.
- 단점: lifecycle 관리 (start/stop, graceful shutdown) 직접 구현 필요. 5일 일정에 과함.

### B. Kafka transactional producer (read_committed)

- 장점: 진정한 exactly-once.
- 단점: MySQL 트랜잭션과 Kafka 트랜잭션을 묶으려면 CDC (Debezium) 필요. 5일 일정 외.

### C. processing_owner 컬럼으로 두 단계 claim

- 장점: 두 인스턴스 동시 실행 시에도 진짜 exactly-once 발행.
- 단점: 스키마 변경 + retry 로직 + claim timeout 정책 필요. 단일 인스턴스 운영 가정
  하에서는 과한 복잡도.

---

## 4. 알려진 trade-off (README 명시)

- **두 인스턴스 동시 실행 시 중복 발행 가능성**: SKIP LOCKED 가 트랜잭션 1 안에서만
  유효 (lock 해제 후 publish). Server C UNIQUE constraint 로 흡수.
- **유령 재고**: B JVM 크래시 (Redis 차감 후 / Outbox INSERT 전) 시 reconciliation
  미구현 — 프로덕션 진화 방향.
- **Topic 자동 생성**: KafkaAdmin 이 부팅 시 `NewTopic` 빈 처리. 운영에선 별도 cli 로
  사전 생성 권장 (broker 권한 격리).

---

## 5. 코드 위치

- 도메인 — `server-b/.../domain/CouponIssueOutbox.java`,
  `CouponIssuedEvent.java`, `CouponIssueOutboxRepository.findUnpublishedForUpdate`
- 외부 계약 — `server-b/.../application/CouponIssuedEventPayload.java`
- 포트 — `server-b/.../application/CouponIssuedEventPublisher.java`,
  `CouponIssuedEventPublishException.java`
- 인프라 — `server-b/.../infrastructure/kafka/KafkaConfig.java`,
  `KafkaCouponIssuedEventPublisher.java`,
  `infrastructure/persistence/CouponIssueOutboxJpaRepository.findUnpublishedForUpdate`
- 스케줄러 — `server-b/.../application/OutboxPoller.java` + `ServerBApplication`
  `@EnableScheduling`
- 테스트 — `server-b/src/test/.../application/OutboxPollerTest.java`,
  `OutboxPollerIT.java`,
  `infrastructure/kafka/KafkaCouponIssuedEventPublisherTest.java`
