# 상태 다이어그램

## 📚 문서 목록

- [요구사항 분석](requirements.md)
- [시스템 아키텍처](architecture.md)
- [다이어그램](diagrams.md)
  - [시퀀스 다이어그램](diagram-sequence.md)
  - **상태 다이어그램** ← 현재 문서
- [ERD](erd.md)
- [API 명세](api-spec.md)

[← README](../../README.md)

---

## 1. 쿠폰 (UserCoupon) 상태

`UserCouponStatus` enum 의 4 가지 상태와 전이 (Server C 의 `user_coupon.status`).

- **SUCCESS** — 정상 발급, 사용 가능 상태 (`code` 부여)
- **SOLD_OUT** — 매진 (재고 차감 시점 `available_count = 0`)
- **FAILED** — 발급 실패 (이벤트 만료 / 사전 검증 실패)
- **USED** — 사용 완료 (`used_at` 기록, 재사용 불가)

```mermaid
stateDiagram-v2
    [*] --> 발급_시도: 사용자 issue-request<br/>(Kafka consume by Server C)

    state 발급_시도 <<choice>>
    발급_시도 --> SUCCESS: 재고 차감 성공<br/>+ user_coupon INSERT
    발급_시도 --> SOLD_OUT: available_count = 0<br/>(비관적 락 후 재고 부족)
    발급_시도 --> FAILED: 이벤트 만료<br/>or 검증 실패

    SUCCESS --> USED: POST /redeem<br/>(@Version 낙관락 성공)
    SUCCESS --> SUCCESS: 멱등 replay<br/>(같은 user 동일 code 재호출 → 200 newlyRedeemed=false)

    SOLD_OUT --> [*]
    FAILED --> [*]
    USED --> [*]

    note right of SUCCESS
        사용 가능 상태
        - user_coupon.code UNIQUE 부여
        - (user_id, coupon_type_id) UNIQUE → 1 인 1 장 보장
    end note

    note right of USED
        used_at 기록
        - 동시 redeem 은 @Version 충돌 → 1 명만 USED 전이
        - 다른 호출은 409 RACE_RETRY
    end note

    note left of FAILED
        보상 흐름은 두지 않음
        - 발급 실패는 사용자가 재요청 (1 인 1 장 UNIQUE 가 자연 멱등)
    end note
```

### 전이 규칙 요약

| From | To | 트리거 | 메커니즘 |
|---|---|---|---|
| (없음) | `SUCCESS` | Kafka consume 후 재고 차감 성공 | `coupon_type_inventory` 비관적 락 + `user_coupon` INSERT |
| (없음) | `SOLD_OUT` | 비관적 락 획득 후 `available_count = 0` | 동일 트랜잭션 |
| (없음) | `FAILED` | 이벤트 만료 / 사전 검증 실패 | 트랜잭션 진입 전 거부 |
| `SUCCESS` | `USED` | `POST /redeem` | `@Version` 낙관적 락 (ADR-007) |

> `SOLD_OUT` / `FAILED` / `USED` 는 **terminal**. 상태에서 빠져나오는 전이 없음 (멱등성 + 단순성).

---

## 2. 이벤트 (Event) 상태

`EventStatus` enum 의 4 가지 상태와 전이 (Server C 의 `event.status`). 운영자가 제어하거나, 시간 기반 자동 전이.

- **CREATED** — 생성됨, 시작 전 (사용자 발급 요청 거부)
- **IN_PROGRESS** — 진행 중 (발급 가능 상태, **캐시 백그라운드 갱신 대상**)
- **ENDED** — 종료됨
- **CANCELLED** — 운영자 취소 (어느 상태에서나 진입 가능)

```mermaid
stateDiagram-v2
    [*] --> CREATED: 이벤트 생성<br/>(운영자 / 어드민)

    CREATED --> IN_PROGRESS: started_at 도달
    IN_PROGRESS --> ENDED: ended_at 도달

    CREATED --> CANCELLED: 운영자 취소
    IN_PROGRESS --> CANCELLED: 운영자 취소

    ENDED --> [*]
    CANCELLED --> [*]

    note right of IN_PROGRESS
        EventCacheRefresher 의 갱신 대상
        - @Scheduled 60s 주기
        - Redis SET event:{id} (TTL 300s)
        - Refresh-Ahead → stampede 회피
    end note

    note left of CREATED
        시작 전 상태
        - 발급 요청은 Server C 검증에서 FAILED
        - 캐시 갱신 대상 아님 (자연 만료 허용)
    end note

    note right of CANCELLED
        어느 상태에서나 진입 가능
        - 캐시는 자연 만료 (refresher 대상에서 제외)
    end note
```

### 전이 규칙 요약

| From | To | 트리거 | 비고 |
|---|---|---|---|
| (없음) | `CREATED` | 어드민 이벤트 등록 | — |
| `CREATED` | `IN_PROGRESS` | `started_at` 도달 (시간 기반) | 발급 가능 상태 진입 |
| `IN_PROGRESS` | `ENDED` | `ended_at` 도달 (시간 기반) | 자연 종료 |
| `CREATED` / `IN_PROGRESS` | `CANCELLED` | 운영자 강제 취소 | 보상 / 환불은 별도 운영 |

> `IN_PROGRESS` 만 `EventCacheRefresher` 의 백그라운드 갱신 대상. 빈번 조회 트래픽이 없는 다른 상태는 stampede 위험이 작아 자연 만료 허용.

---

## 3. 발급 요청 audit (IssueRequest) 상태

`IssueRequestStatus` enum 의 4 가지 상태 (Server A 의 `issue_request.status`). A 진입 시점에 결정되는 audit log — 발급 결과의 권위는 Server C 의 `user_coupon` 이고, 본 enum 은 **A 가 본 결과** 의 기록.

- **ACCEPTED** — B 가 Redis 적재 + Kafka publish 완료
- **DUPLICATE** — 같은 `(user_id, coupon_type_id)` 가 이미 pending / 발급됨 (1 인 1 장 자연 차단)
- **SOLD_OUT** — A 진입 단계에서 negative cache 단락으로 즉시 매진 결정 (ADR-011)
- **REJECTED** — B 호출이 Resilience4j Circuit Breaker OPEN / B 일시 장애로 실패

```mermaid
stateDiagram-v2
    [*] --> 진입: POST /api/v1/coupons/issue-request

    state 진입 <<choice>>
    진입 --> SOLD_OUT: ADR-011 cache HIT<br/>(EXISTS coupon:available)
    진입 --> B_호출: cache MISS

    state B_호출 <<choice>>
    B_호출 --> ACCEPTED: B 가 200<br/>{ status: ACCEPTED }
    B_호출 --> DUPLICATE: B 가 200<br/>{ status: DUPLICATE }
    B_호출 --> REJECTED: B 5xx · CB OPEN · timeout

    ACCEPTED --> [*]
    DUPLICATE --> [*]
    SOLD_OUT --> [*]
    REJECTED --> [*]

    note right of ACCEPTED
        per-request commit (ADR-010)
        - audit 만 기록, 발급 권위는 C
        - 사용자 응답 200
    end note

    note left of SOLD_OUT
        ADR-011 단락
        - B/C 호출 자체 skip
        - 응답 latency 가장 짧음
    end note

    note right of REJECTED
        503 + Retry-After: 5
        - 본문은 INTERNAL_ERROR
        - 5초 후 재시도 안내
    end note
```

### 전이 규칙 요약

| 결정 시점 | status | 사용자 응답 | 후속 처리 |
|---|---|---|---|
| A 진입 캐시 단락 | `SOLD_OUT` | 200 `{ status: SOLD_OUT }` | (없음) — 종결 |
| B 응답 200 ACCEPTED | `ACCEPTED` | 200 `{ status: ACCEPTED }` | C 가 비동기 처리 → 폴링/내쿠폰 조회 |
| B 응답 200 DUPLICATE | `DUPLICATE` | 200 `{ status: DUPLICATE }` | (없음) — 이미 pending/발급됨 |
| B 5xx / CB OPEN / timeout | `REJECTED` | 503 + `Retry-After: 5` | 클라이언트 재시도 |

> 모든 상태가 terminal — A 의 audit 은 한 요청에 한 번만 결정 (per-request commit, ADR-010).

---

## 4. B 의 사용자 신청 상태 (PendingIssue, Redis)

`IssuePendingStatus` enum 의 4 가지 상태 (Server B 의 Redis HASH `issue:pending:{userId}:{couponTypeId}` 의 `status` 필드). 사용자가 폴링으로 직접 보는 lifecycle.

- **PENDING** — 신청 적재됨, C 처리 대기
- **SUCCESS** — 발급 완료 (code 부여)
- **SOLD_OUT** — 매진 결과
- **FAILED** — 이벤트 만료 등 도메인 거부 또는 30s SLA 도달 후 마감

```mermaid
stateDiagram-v2
    [*] --> PENDING: B accept · savePendingIfAbsent<br/>(HASH + ZSET 등록)

    PENDING --> SUCCESS: result consumer<br/>or 스케줄러 lookup HIT
    PENDING --> SOLD_OUT: result consumer<br/>or 스케줄러 lookup HIT
    PENDING --> FAILED: result consumer<br/>or 스케줄러 lookup HIT
    PENDING --> FAILED: 스케줄러 cap 도달<br/>(publishAttempts ≥ 3)

    SUCCESS --> [*]: 24h TTL 자연 만료
    SOLD_OUT --> [*]: 24h TTL 자연 만료
    FAILED --> [*]: 24h TTL 자연 만료

    note right of PENDING
        ZSET 에 등록되어 스케줄러가 잡아감
        - publishAttempts 카운터 추적
        - 10s 초과 시 스케줄러 재발행
    end note

    note left of SUCCESS
        HASH 에 status + code 동시 갱신
        markResult(SUCCESS, code)
        - ZSET 에서 ZREM (스케줄러 재진입 차단)
        - HASH 는 24h 동안 폴링 응답용으로 유지
    end note

    note right of FAILED
        두 경로:
        1. C 가 도메인 거부 (이벤트 만료 등) → result topic
        2. 스케줄러가 publishAttempts ≥ 3 도달 → 30s SLA 종결
    end note
```

### 전이 규칙 요약

| From | To | 트리거 | 메커니즘 |
|---|---|---|---|
| (없음) | `PENDING` | B accept (cache miss) | `HSETNX` (first-write) → `HSET` 나머지 필드 + `ZADD` |
| `PENDING` | `SUCCESS` / `SOLD_OUT` / `FAILED` | result consumer (Kafka) | `markResult` — HASH update + ZSET ZREM |
| `PENDING` | `SUCCESS` / `SOLD_OUT` / `FAILED` | 스케줄러 lookup C HIT | 동일 (`markResult`) |
| `PENDING` | `FAILED` | 스케줄러 cap 도달 | `markResult(FAILED)` — `publishAttempts ≥ maxPublishAttempts` |

> HASH 와 ZSET 의 분리:
> - **HASH** = 사용자 폴링 응답용 상세 데이터 (24h TTL, 결과 후에도 유지)
> - **ZSET** = 스케줄러의 work queue (결과 확정 시 즉시 제거 → 재발행 대상에서 빠짐)

> ZSET ZREM 이 "정상 처리됨 = 재발행 대상에서 제외" 라는 신호를 표현. 스케줄러는 ZSET 만 보므로 SUCCESS/SOLD_OUT/FAILED 종결된 항목은 다시 잡히지 않음.

---

## 5. Outbox row 상태 (참고)

`OutboxEventStatus` enum 의 2 가지 상태 (Server C 의 `outbox_event.status`). **이 status 는 publish 메타-상태이며 발급 결과(payload.status)와 별개**임에 유의.

- **PENDING** — 아직 Kafka 로 보내지 않은 row (INSERT 직후 default)
- **PUBLISHED** — OutboxPoller 가 Kafka publish 성공 후 갱신

```mermaid
stateDiagram-v2
    [*] --> PENDING: CouponIssueProcessor 가 INSERT<br/>(트랜잭션 안 — 발급 결과 확정 시점)

    PENDING --> PUBLISHED: OutboxPoller 가 Kafka publish 성공
    PENDING --> PENDING: publish 실패 → 다음 주기 재시도<br/>(at-least-once)

    PUBLISHED --> [*]

    note right of PENDING
        모든 결과가 PENDING 으로 시작
        - SUCCESS / SOLD_OUT / FAILED 페이로드 모두
        - status = "publish 메타-상태", 결과는 payload 안
    end note

    note left of PUBLISHED
        한 번 PUBLISHED 되면 종결
        - poller 가 다시 선택하지 않음 (중복 publish 방지)
    end note
```

> at-least-once 보장: publish 직후 status 갱신 직전에 죽으면 다음 cycle 이 같은 row 를 한 번 더 publish. B 의 result consumer 가 멱등 처리하므로 안전.
