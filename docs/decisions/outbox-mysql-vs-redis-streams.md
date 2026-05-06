# Outbox 구현: MySQL Outbox + 보상 트랜잭션 vs Redis Streams atomic

> **Status**: 결정 (2026-05-06)
> **결정**: 옵션 B (MySQL Outbox + Redis 보상 트랜잭션) 채택
> **연관**: CLAUDE.md ADR-002, ADR-003, ADR-006, §3 평가 항목 ②
>
> 이 문서는 Day 2 PR #8 머지 직후 다시 검토한 정합성 처리 전략의 토론 기록이다.
> 결정 자체는 ADR-002 와 동일하나, 다른 합리적 대안 (Redis Streams) 을 명시적으로
> 거부한 근거를 남긴다. README 통합 시 "트레이드오프" 섹션의 후보.

---

## 1. 문제

정합성 처리에서 Redis DECR (재고 차감) 과 메시지 발행을 atomic 하게 묶고 싶다.
하지만 Redis 와 MySQL 은 한 트랜잭션으로 묶을 수 없다.

두 가지 옵션 사이에서 검토:

- **옵션 A**: Outbox 를 Redis Streams 로 두고 Lua script 로 `DECR + XADD` 를 atomic
  하게 처리. 단순하지만 NoSQL 일변도.
- **옵션 B**: Outbox 는 MySQL 에 두고, Redis 차감 성공 후 MySQL INSERT 실패 시
  Redis INCR 로 보상 트랜잭션. Saga 패턴 색깔이 더 진하지만 복잡도 증가.

솔로 5일 프로젝트 + 채용 평가 관점에서 어느 쪽이 합리적인가.

---

## 2. 결론

**옵션 B 유지. 보상 트랜잭션을 단순하게 (~10 줄) 구현.**

이유는 아래 §3 ~ §5 에서 설명한다.

---

## 3. 옵션 B 를 선택한 핵심 근거

### 3.1 평가 관점 시그널 (가장 중요)

CLAUDE.md §3 5축 중 **② 분산 정합성 + 멱등성** 이 평가 핵심이고, 본 과제 시나리오
전체가 "Redis 와 MySQL 을 한 트랜잭션으로 못 묶는 상황을 어떻게 다루는가" 를
보겠다는 의도다.

- **옵션 A (Redis Streams atomic)** → 정합성 문제 자체를 회피. 평가자 입장에선
  "이 사람은 분산 정합성의 어려운 부분을 우회했다" 시그널.
- **옵션 B (보상 트랜잭션)** → "atomic 이 안 되는 걸 알고, choreography saga 로
  명시적으로 다뤘다" 시그널. 코드에 `try { mysqlInsert() } catch { redis.incrby() }`
  라는 한 줄이 평가 항목 ② 의 핵심을 그대로 보여준다.

채용 평가는 "어려운 문제에 대한 자각" 을 본다. 옵션 A 는 그 자각이 코드에 안 남는다.

### 3.2 CLAUDE.md ADR 정합

이미 다음이 옵션 B 위에서 결정됨:

- ADR-002: Saga (Choreography) + Outbox 패턴
- ADR-006: Database per Service
- §5.3: Server C Kafka consumer + UNIQUE constraint 멱등성

옵션 A 로 가면 위 결정들을 광범위하게 재작성해야 한다:

- ADR-002 다시 작성 (Saga 색깔 약화)
- ADR-006 영향 (server_b MySQL 제거 시 자원 분리 명분 약화)
- §5.3 Server C Kafka consumer → Redis Streams consumer 또는 Redis → Kafka bridge 추가
- §7 기술 스택에서 Kafka 의 역할 축소

5일 일정에 ADR 광범위 재작성은 시간 비용이 크다. PR #8 (이미 머지됨) 도 폐기/변경.

### 3.3 Server C 와의 결합도

옵션 A 로 가면 Server C 가 Redis Streams 를 직접 consume 해야 한다. 그러면:

- Server C → server-b 의 Redis 에 의존 → Database per Service 위반에 가까움
- 또는 Redis Streams → Kafka bridge 라는 또 다른 인프라 (1 vCPU 제약에서 부담)

옵션 B 의 Kafka 경계가 가장 깔끔하다.

---

## 4. 옵션 A 의 매력 — 인정하되 본 과제와는 안 맞음

옵션 A 의 진짜 강점:

- Redis Streams = Kafka-lite (Consumer Group, ack, replay 다 됨)
- DECR + XADD 한 Lua 로 진짜 atomic — **보상 트랜잭션이라는 코드 자체가 사라짐**
- 인프라 단순화 (Kafka 제거 가능)
- 1 vCPU 제약에 더 친화적

이건 "MySQL Outbox + Kafka" 가 과한 인프라라고 판단되는 환경 (소규모 startup,
운영 인력 부족) 에서 합리적인 선택이다. 하지만 본 과제는 채용 평가가 명시적으로
Kafka + 분산 정합성을 보고 싶어하는 환경이라 다르다.

### 옵션 A 의 숨은 비용 (놓치기 쉬움)

- **Redis 영속성**: AOF `appendfsync everysec` 기본 = 1 초 데이터 유실 가능.
  RDB 만이면 더 큼. MySQL 의 ACID 와 다른 보장. Outbox 가 본질적으로 "영속 이벤트
  로그" 인데 Redis 는 약하다.
- **Stream 크기 관리**: `MAXLEN` 또는 명시적 trim. 안 하면 메모리 폭증
  (1 vCPU / 2 GB 환경에서 위험).
- **Cluster 분산**: Redis Streams 는 단일 hash slot. Cluster mode 에서도 한 Stream
  은 한 노드에 묶임 → Redis 자체가 SPOF.

---

## 5. 보상 트랜잭션은 두려워할 일이 아님

옵션 B 의 보상이 "복잡하다" 는 인식이 실제보다 과장돼 있다. PR #9 의 핵심 흐름:

```java
// CouponIssueService (server-b)
@Transactional  // MySQL Outbox INSERT 만 트랜잭션 안. Redis 는 밖.
public IssueResult issue(IssueCommand cmd) {
    // 1. Redis Lua: DECR + 쿠폰 코드 생성 (atomic)
    LuaResult lua = redisStockClient.tryDecrAndIssue(
        cmd.eventId(), cmd.userId(), cmd.idempotencyKey());
    if (lua.isSoldOut()) return IssueResult.soldOut();

    try {
        // 2. MySQL Outbox INSERT
        outboxRepository.save(CouponIssueOutbox.create(
            lua.couponCode(), cmd.idempotencyKey(),
            buildPayload(cmd, lua), Instant.now()));
        return IssueResult.succeeded(lua.couponCode());
    } catch (DataAccessException e) {
        // 3. 보상: Redis INCR 로 재고 복구 + alert
        redisStockClient.compensate(
            cmd.eventId(), cmd.userId(), lua.couponCode());
        log.error("outbox insert failed, compensated: code={}, idem={}",
            lua.couponCode(), cmd.idempotencyKey(), e);
        throw new BusinessException("ISSUE_TEMPORARILY_UNAVAILABLE", e);
    }
}
```

- 보상 코드 = `catch + compensate + 로그` ~5 줄.
- `compensate` 는 Redis Lua: `INCRBY` + `DEL coupon:code:{code}` (Lua 로 atomic).

복잡하지 않다.

### 5.1 알려진 위험: B 가 Redis 차감 후 / MySQL INSERT 전에 죽는 경우

진짜 어려운 케이스: Redis Lua 성공 → JVM 크래시 → MySQL INSERT 도 보상도 못 함 →
유령 재고 (Redis 는 차감됐는데 MySQL Outbox 에 없음).

**5일 일정 내 처리법**:

- README §"트레이드오프" 에 한 줄: *"B 의 비정상 종료로 인한 유령 재고는 별도
  reconciliation job (Redis 발급 코드 vs MySQL Outbox diff) 으로 복구. 본 과제에선
  미구현 — 프로덕션 진화 방향으로 명시."*
- 평가자에게 "위험을 인지하고 있다" 시그널은 코드보다 README 한 줄이 더 강하다.

이 위험은 **옵션 A 도 동일하게 존재**한다 (Redis Streams 의 AOF/RDB 영속 정책에
따라 1 초 유실 가능 → 동일하게 reconciliation 필요).

---

## 6. 다른 옵션들

### 옵션 C: Outbox INSERT 실패 시 보상 INCR 도 안 함, 그냥 503

- B 가 Redis 차감 + MySQL INSERT 만. 실패 시 사용자에게 503 반환.
- 위험: 정상 케이스에서 사용자가 "재시도" 하면 idempotency-key 가 같으니 멱등 처리
  → 다른 코드로 재발급? 같은 코드로? 사용자 경험이 일관되지 않음.
- **거부**: 현실적이지 않음.

### 옵션 D: Debezium CDC 로 MySQL binlog → Kafka

- B 가 Redis 차감 + MySQL INSERT 만. Outbox poller 도 필요 없음 (CDC 가 자동 발행).
- 진짜 atomic 이 아니어도 (Redis ↔ MySQL) 보상 + CDC 조합으로 깔끔.
- CLAUDE.md ADR-002 가 이미 "프로덕션 진화 방향으로 CDC" 명시.
- **5일 안에 직접 구현은 비효율**. 옵션 B 위에서 README 진화 방향으로 메모.

---

## 7. 비교 표

| 기준 | 옵션 A (Redis Streams) | 옵션 B (MySQL Outbox + 보상) |
|---|---|---|
| 평가 항목 ② 시그널 | 약함 (정합성 회피) | **강함** (보상 트랜잭션 명시) |
| CLAUDE.md ADR 정합 | ADR-002, 005, 006 광범위 변경 | 그대로 유지 |
| Server C 와의 결합도 | 높음 (Redis 의존 또는 bridge) | 낮음 (Kafka 경계 깔끔) |
| 진짜 atomic | ✅ | ❌ (보상으로 보완) |
| 5일 안 완성 가능성 | 중 (ADR 재작성 비용) | 높음 (~10 줄 보상) |
| 영속성 | 약함 (Redis AOF) | 강함 (MySQL ACID) |
| 1 vCPU 제약 | 더 친화적 | OK |
| Redis SPOF | 높음 (Stream = 단일 hash slot) | 분산 가능 |
| 코드 단순함 | ✅ | ✅ (인식보다 단순) |

---

## 8. 실행 계획 (PR #9 에 반영)

1. Redis Lua 스크립트: `DECR shard + 쿠폰 코드 생성 + coupon:code:{code} HSET`
   을 atomic 하게.
2. `CouponIssueService` 가 Lua 결과를 받아 MySQL Outbox INSERT.
3. INSERT 실패 시 보상 Lua 스크립트 호출: `INCRBY shard + DEL coupon:code:{code}`.
4. 보상 후 사용자에게 503 + retry-after.
5. README 트레이드오프 섹션에 "유령 재고 reconciliation 미구현 — 프로덕션 진화
   방향" 한 줄 추가.

PR #9 에서 위 5 단계를 모두 다룬다. 동시성 통합 테스트 (10000 장 + 10000 동시 요청
→ 정확히 10000 발급 + Outbox 10000 행) 는 본 결정의 정확성을 검증하는 핵심.
