# 장애 시나리오 — 어디서 죽으면 어떻게 되는가

> 분산 시스템의 각 단계에서 부분 장애가 발생할 때의 영향과 본 과제의 처리. 운영 진입 시 진화
> 방향까지 함께. 평가자에게 "위험 인식 + 트레이드오프 결정" 의 흔적을 보여주는 문서.

본 과제의 핵심 정합성 보호는 **Server C 의 `(user_id, idempotency_key) UNIQUE` + `code UNIQUE`**
가 의미적 권위. 그 위에서 각 단계의 부분 장애를 어떻게 흡수 / 인지 / 미해결로 남겼는지 정리.

---

## 1. 발급 흐름의 장애

```
User → A → B → (Outbox) → poller → Kafka → C
```

### 1.1 Server A 가 Server B 호출 실패 (timeout / 5xx)

| 항목 | 내용 |
|---|---|
| 증상 | RestClient timeout (200ms) 또는 server-b 5xx |
| 본 과제 처리 | Resilience4j Circuit Breaker 가 흡수 — 실패율 임계 초과 시 OPEN, server-a 가 503 + Retry-After 즉시 반환 (ADR-001) |
| 사용자 영향 | 503 응답, idempotency 캐시는 503 저장 안 함 (PR #6 결정 — 2xx 만 캐싱) — 재시도 가능 |
| Idempotency-Key 영향 | 미적재 → 재시도 시 issue_request 가 INSERT 되거나 (성공 시) 캐시 hit (이전 성공 시) |
| 진화 방향 | 보다 정교한 fallback (예: 후속 처리 큐) — 본 과제는 단순 503 |

### 1.2 Server B 가 Redis 차감 후 / Outbox INSERT 전 JVM 크래시

**가장 위험한 단일 실패 시나리오** — 본 과제에서 의도적으로 미해결로 남김.

| 항목 | 내용 |
|---|---|
| 증상 | Redis Lua 가 stock --, coupon code 생성. 그 후 RDBMS Outbox INSERT 직전 JVM kill |
| 결과 상태 | Redis stock 1 차감 + MySQL Outbox 행 무 → **유령 재고**. 사용자에게 응답도 못 감 |
| 본 과제 처리 | **미구현** — README + scope-decisions.md 에 트레이드오프 명시. 운영 진화 방향: reconciliation job (Redis 발급 코드 vs Outbox diff) 또는 CDC 기반 outbox |
| 빈도 영향 | 단일 인스턴스 + JVM crash 라는 매우 드문 케이스. 본 과제 평가 5축 핵심 아님 |
| 진화 방향 | (a) reconciliation job — 5분 간격으로 Redis 발급 코드 vs Outbox 비교, 누락분 보상 INCR. (b) CDC + transactional outbox |

근거: [`../decisions/outbox-mysql-vs-redis-streams.md`](../decisions/outbox-mysql-vs-redis-streams.md) §5.1

### 1.3 Server B Outbox INSERT 가 실패 (UNIQUE 위반 / DB 일시 오류)

| 항목 | 내용 |
|---|---|
| 증상 | Redis Lua 차감 성공 후 `outboxRepository.save(outbox)` 가 DataAccessException |
| 본 과제 처리 | catch → **보상 트랜잭션** (Lua: `INCRBY shard + DEL coupon:code:{code}`) → BusinessException("ISSUE_TEMPORARILY_UNAVAILABLE") → server-a 503 |
| 결과 상태 | Redis 차감 복구 + Outbox 무 + 사용자 503 응답 — 일관성 회복 |
| Idempotency 보호 | UNIQUE (`user_id`, `idempotency_key`) 위반은 별도 분기 — 같은 user 가 같은 idem 으로 재시도 시 server-b idem 캐시 (`coupon:idem:{userId}:{key}`) 가 hit, 첫 발급 결과 그대로 반환. 캐시 miss + DB UNIQUE 충돌 시는 server-a 의 user-scoped 캐시가 흡수 |
| 진화 방향 | DLT 또는 replay. 5일 일정 외 |

### 1.4 Outbox poller 가 Kafka 발행 실패

| 항목 | 내용 |
|---|---|
| 증상 | broker down / network partition 등으로 KafkaTemplate.send().get(timeout) 실패 |
| 본 과제 처리 | `CouponIssuedEventPublishException` throw → poller 가 markPublished 건너뜀 → 다음 cycle (200ms) 에서 재시도 |
| 결과 상태 | published=false 유지. broker 회복 후 자동 재시도 |
| 부작용 | 재시도 시 같은 메시지가 두 번 발행될 가능성 (markPublished 직전 크래시 케이스) — Server C UNIQUE 가 흡수. 의미적 exactly-once |
| 진화 방향 | broker 미회복 누적 시 outbox 행 backlog. Day 4 측정 시 정량 검증 |

근거: [`../decisions/server-b-outbox-poller-kafka.md`](../decisions/server-b-outbox-poller-kafka.md) §2.4

### 1.5 Server C consumer 가 처리 중 일시 DB 오류

| 항목 | 내용 |
|---|---|
| 증상 | Kafka 메시지 수신 후 JPA save 가 transient DataAccessException |
| 본 과제 처리 | listener 가 catch 안 함 → throw → Spring Kafka `DefaultErrorHandler` retry (default 10회 + 1초 backoff) |
| 결과 상태 | retry 모두 실패 시 stop — 운영 알림 + 수동 복구 가정 |
| 진화 방향 | DLT + 알림 + replay 도구 — README 트레이드오프 |

### 1.6 Server C consumer 가 중복 메시지 수신 (broker retry 또는 poller 재발행)

| 항목 | 내용 |
|---|---|
| 증상 | 같은 `(user_id, idempotency_key)` 또는 같은 `code` 의 메시지가 두 번째 도착 |
| 본 과제 처리 | DB UNIQUE 위반 → DataIntegrityViolationException → listener 가 catch + INFO 로깅 + ack (재시도 루프 차단). 의미적 exactly-once 의 권위 |
| 결과 상태 | DB 1 행만 존재. Kafka offset commit 으로 다시 안 들어옴 |

### 1.7 Server C consumer 가 잘못된 JSON / payload (poison pill) 수신

| 항목 | 내용 |
|---|---|
| 증상 | Jackson 역직렬화 실패 (`JsonProcessingException`) — payload schema 불일치 또는 couponCode 길이 invalid (`CouponIssuedEventPayload` compact constructor 검증) |
| 본 과제 처리 | listener 가 catch + ERROR 로깅 + ack (poison pill 흡수, retry 루프 회피) |
| 진화 방향 | DLT 로 격리 + 분석 |

---

## 2. 사용 (redeem) 흐름의 장애

### 2.1 Server C 가 redeem 호출 시 stale `@Version` (race)

| 항목 | 내용 |
|---|---|
| 증상 | 같은 coupon 동시 redeem — 두 thread 가 같은 version=N 을 read, 첫 commit 후 두 번째 commit 시 affected rows=0 |
| 본 과제 처리 | OptimisticLockingFailureException → GlobalExceptionHandler 가 409 RACE_RETRY 매핑 |
| 클라이언트 처리 | 재시도 시 `findByCode` 가 used_at != null 행 반환 → 멱등 분기로 200 + 기존 redeemedAt |
| 검증 | `RedeemCouponConcurrencyIT.concurrent_redeem_results_in_single_used_at` (5 thread 중 1 newlyRedeemed=true + 4 retry/멱등) |

### 2.2 Server C 가 다른 user 의 coupon 으로 redeem 호출

| 항목 | 내용 |
|---|---|
| 증상 | `X-User-Id` 헤더의 user 와 `coupon.user_id` 불일치 |
| 본 과제 처리 | `RedeemCouponService` 가 `CouponNotFoundException` throw → 404 마스킹 (코드 존재 여부 누설 방지) |
| 운영 디버깅 | log.warn 으로 `owner=A, requester=B` 명시 |

근거: [`../decisions/redeem-idempotency-without-cache.md`](../decisions/redeem-idempotency-without-cache.md) §3.3

---

## 3. 인프라 단의 장애

### 3.1 Redis 단일 노드 장애

| 항목 | 내용 |
|---|---|
| 증상 | Redis 컨테이너 down. server-a 의 Bucket4j / Idempotency cache, server-b 의 stock / idem cache 모두 영향 |
| 본 과제 처리 | Lettuce 의 connect-timeout (1초) 후 server-a 의 GlobalExceptionHandler 가 500. server-a 의 RestClient 호출도 fail |
| 결과 상태 | 모든 발급 차단. Redis 영구 데이터 (재고 카운터) 가 휘발 (AOF/RDB 정책 따라 부분 복구) |
| 진화 방향 | Redis Sentinel / Cluster + 재고 카운터의 영속성 정책 (AOF everysec) + 발급 코드 reconciliation |

### 3.2 MySQL primary 장애

| 항목 | 내용 |
|---|---|
| 증상 | server_a / server_b / server_c 의 primary DB down |
| 본 과제 처리 | HikariCP connection-timeout (3초) 후 모든 발급 / redeem 5xx |
| 결과 상태 | 트랜잭션 in-flight 모두 실패. Outbox poller 도 일시 중단 |
| 진화 방향 | replication + automatic failover (RDS Multi-AZ / Aurora) |

### 3.3 Kafka broker 단일 노드 장애

| 항목 | 내용 |
|---|---|
| 증상 | broker down |
| 본 과제 처리 | producer (server-b poller) 의 retry (5회 + 30초 delivery timeout). consumer (server-c) 의 reconnect |
| 결과 상태 | server-b outbox 의 published=false 행 누적. server-c lag 누적 |
| 진화 방향 | broker cluster (RF=3) + ISR ≥ 2 |

### 3.4 Network partition (서비스 간)

| 항목 | 내용 |
|---|---|
| 증상 | server-a ↔ server-b 또는 server-c ↔ Kafka 등의 network 분리 |
| 본 과제 처리 | server-a CB 가 OPEN → 503. server-c consumer 의 group 이 rebalance 후 재시도 |
| 결과 상태 | partition 동안 발급 차단 (CB OPEN), 재연결 후 정상 |

---

## 4. 운영 알림 (진화 방향)

본 과제는 알림 채널 미구현. 운영 진입 시 다음 메트릭에 대한 알림 권장:

| 메트릭 | source | 임계 | 대응 |
|---|---|---|---|
| server-a 5xx rate | actuator/prometheus | > 1% over 1m | 호출 흐름 점검 |
| server-a CB OPEN 빈도 | resilience4j 메트릭 | > 5/m | server-b 헬스 점검 |
| server-b outbox published=false count | DB query | > 100 | poller 또는 broker 점검 |
| server-c kafka consumer lag | kafka jmx | > 1000 | consumer scale-up 또는 rebalance |
| server-c DLT 메시지 수 | kafka topic size | > 0 | DLT 분석 |
| MySQL replication lag | RDS / mysqld_exporter | > 10s | primary 부하 분산 |

---

## 5. 본 과제의 처리 / 진화 매트릭스

| 위험 | 본 과제 | 운영 진화 |
|---|---|---|
| 1.1 A→B 호출 실패 | CB 503 | 별도 fallback 큐 |
| 1.2 B JVM crash (유령 재고) | **미해결** (인지) | reconciliation job 또는 CDC |
| 1.3 B Outbox INSERT 실패 | 보상 트랜잭션 + 503 | DLT |
| 1.4 poller → Kafka 실패 | 다음 cycle 재시도 | broker cluster |
| 1.5 C consumer DB 일시 오류 | retry → stop | DLT + 알림 |
| 1.6 C 중복 메시지 | UNIQUE 거부 + ack | (해결됨) |
| 1.7 C poison pill | ack + ERROR 로그 | DLT |
| 2.1 redeem race | 409 RACE_RETRY | (해결됨) |
| 2.2 redeem 다른 user | 404 마스킹 | (해결됨) |
| 3.1 Redis down | 발급 차단 | Sentinel / Cluster |
| 3.2 MySQL down | 모든 흐름 차단 | Multi-AZ |
| 3.3 Kafka down | poller / consumer 재연결 | RF=3 |
| 3.4 Network partition | CB OPEN + reconnect | (인프라 영역) |

각 행에서 "본 과제" 가 흡수 / 인지 / 미해결 인지를 명시함으로써, 평가자가 "이 사람이 무엇을 알고
무엇을 안 했는지" 한눈에 파악.

---

## 6. 관련 문서

- [`../decisions/outbox-mysql-vs-redis-streams.md`](../decisions/outbox-mysql-vs-redis-streams.md) — 보상 트랜잭션의 5줄 구현 + 유령 재고 트레이드오프
- [`../decisions/server-b-outbox-poller-kafka.md`](../decisions/server-b-outbox-poller-kafka.md) — at-least-once + UNIQUE 의 의미적 exactly-once
- [`../decisions/redeem-idempotency-without-cache.md`](../decisions/redeem-idempotency-without-cache.md) — redeem 의 도메인 자체 멱등 + 404 마스킹
- [`../decisions/scope-decisions.md`](../decisions/scope-decisions.md) — 의도적으로 미룬 항목들의 종합
