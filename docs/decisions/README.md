# 설계 결정 (ADR) 인덱스

> 본 디렉토리는 **결정 + 근거 + 거부된 대안** 을 기록한다. 평가자가 코드만 보고는 알 수 없는 의도와
> 트레이드오프를 한곳에서 추적할 수 있게.
>
> CLAUDE.md §6 의 7개 ADR 은 시스템 전반의 결정. `docs/decisions/` 의 별도 문서는 그 이후의 구체화 / 정합화 / 결정.

---

## 1. CLAUDE.md 의 7개 ADR (시스템 골격)

| # | ADR | 한 줄 요약 | 거부된 대안 |
|---|---|---|---|
| 001 | [A→B 동기, B→C 비동기](../../CLAUDE.md) | 사용자 응답 latency 와 영구 저장 분리 — 선착순 UX 가 즉시 결과를 요구 | 전체 비동기 + 폴링 (사용자 F5 연타로 트래픽 폭증) |
| 002 | [Saga (Choreography) + Outbox](../../CLAUDE.md) | Choreography 가 1 vCPU 에 경량. CDC 는 진화 방향 | Orchestrator (1 vCPU 부담), CDC (5일 일정 외) |
| 003 | [Redis Lua 재고 관리](../../CLAUDE.md) | atomic DECR + 음수 방지 + 코드 생성 한 번에 | RDBMS row lock (1 vCPU 락 경합으로 처리량 폭락) |
| 004 | [Idempotency-Key 헤더](../../CLAUDE.md) | user-scoped `(user_id, idem)` 3 단계 — A Redis + B Redis + C UNIQUE | idem-only UNIQUE (다른 user 가 우연히 같은 UUID 시 격리 깨짐) |
| 005 | [Rate Limiting on A](../../CLAUDE.md) | Bucket4j Lettuce, 사용자당 10 req/sec | downstream 에 부담 위임 |
| 006 | [Database per Service](../../CLAUDE.md) | server_a / server_b / server_c schema 분리 | 모든 서비스가 같은 DB 공유 |
| 007 | [Redeem 낙관적 락](../../CLAUDE.md) | `@Version` 자동 동작 + 클라이언트 재시도 | 비관 락 (1 vCPU 오버헤드, 트래픽 급증 시 락 경합) |

---

## 2. 추가 결정 문서 (구체화 / 정합화)

본 디렉토리에서 다루는 결정들 — CLAUDE.md ADR 위에서 발생한 구체적 선택 / 정합화 / 보완.

### 2.1 [Outbox 구현 — MySQL Outbox vs Redis Streams](./outbox-mysql-vs-redis-streams.md)

> ADR-002 의 구체화. atomic 안 되는 환경 (Redis ↔ MySQL) 을 어떻게 다룰지 옵션 4개 비교 (A: Redis Streams, B: MySQL Outbox + 보상, C: 보상 없이 503, D: Debezium CDC) 후 **옵션 B 채택**.

핵심 시그널: "정합성 회피하지 않고 보상 트랜잭션 ~10 줄 코드로 명시". 평가자에게 "어려운 문제에 대한 자각" 표현.

### 2.2 [Server B idempotency cache user-scoped 정합화](./server-b-user-scoped-idempotency-cache.md)

> ADR-004 의 정합화. server-b 의 idem 1차 캐시 키를 `coupon:idem:{userId}:{key}` 로 변경 (PR #12).

이전: idem-only 키 → 다른 user 의 우연한 같은 idem 시 충돌. **3 단계 보호 (server-a + server-b + server-c) 모두 user-scoped 정합**.

### 2.3 [Outbox poller + Kafka producer 설계](./server-b-outbox-poller-kafka.md)

> ADR-002 의 구체화. 5결정점:
> - `@Scheduled(fixedDelay=200ms)` + SKIP LOCKED 안전망
> - producer `acks=all + enable.idempotence=true`
> - topic `coupon.issued` partitions=3 RF=1 key=userId
> - 트랜잭션 분리 (SELECT → publish → markPublished)
> - at-least-once + Server C UNIQUE = **의미적 exactly-once**

거부: Worker thread + ExecutorService (5일 외), Kafka transactional producer (CDC 필요), processing_owner 컬럼 (단일 인스턴스에 과한 복잡도).

### 2.4 [Server A 튜닝 — 부하 측정 후 결정](./server-a-tuning-load-test-driven.md)

> ADR-005 의 구체화. Server A 의 batch insert / 명시적 백프레셔는 **Day 4 부하 측정으로 병목을 정량 식별 후 도입 결정**.

평가자 시그널: "premature optimization 회피 — 측정 우선". 5축 평가 ⑤ (사이징) 의 일부.

### 2.5 [Redeem 멱등성 — 별도 캐시 미도입](./redeem-idempotency-without-cache.md)

> ADR-004 / ADR-007 의 구체화. redeem 은 도메인 자체 멱등 (`used_at` 한 번 set) 으로 충분. 별도 idem 캐시 / 테이블 / 응답 캐시 미도입.

거부: 별도 redeem 테이블 (보호 가치 < 비용), 응답 캐시 only (시간 정밀도 mismatch 위험).

### 2.6 [Scope 결정 — 의도적으로 안 한 것](./scope-decisions.md)

> 5일 일정 안에서 평가 5축 외로 의도적으로 미룬 항목 종합. 헥사고날 / Saga Orchestrator / DLT / 분산 트레이싱 / JWT / 운영 stock endpoint / GitHub Actions CI 등.

각 항목에 미적용 이유 + 진화 방향 명시 — "시간 압박 안에서 의식적으로 우선순위를 정한 흔적".

---

## 3. ADR 작성 가이드

본 디렉토리에 새 ADR 을 추가할 때:

```markdown
# 결정 제목 (한 줄)

> **Status**: 결정 (YYYY-MM-DD)
> **결정**: 핵심 선택을 한 줄로
> **연관**: CLAUDE.md ADR-XXX, PR #N

## 1. 문제
무엇을 결정해야 했는가. 옵션 N개 나열.

## 2. 결정
선택한 옵션 + 흐름 / 코드 위치.

## 3. 핵심 근거
- 평가 시그널 (가능하면 명시)
- 트레이드오프
- 영향 범위

## 4. 거부된 대안
각 옵션의 매력 + 거부 이유.

## 5. Trade-off
이 결정이 만든 한계.

## 6. 검증 / 후속
단위 / IT / e2e 어디서 검증되는지. 진화 방향.
```

핵심: **거부된 대안의 근거를 명시** — "왜 다른 옵션을 안 썼나" 가 가장 강한 평가 시그널.
