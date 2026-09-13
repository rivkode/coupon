# 시퀀스 다이어그램

## 📚 문서 목록

- [요구사항 분석](requirements.md)
- [시스템 아키텍처](architecture.md)
- [다이어그램](diagrams.md)
  - **시퀀스 다이어그램** ← 현재 문서
  - [상태 다이어그램](diagram-state.md)
- [ERD](erd.md)
- [API 명세](api-spec.md)

[← README](../../README.md)

---

## 1. 쿠폰 발급 요청 — `POST /api/v1/coupons/issue-request`

Server A → Server B → Server C 분산 흐름. 사용자 응답은 **즉시 종결** (`ACCEPTED / DUPLICATE / SOLD_OUT`), 실제 발급(재고 차감)은 비동기.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant A as Server A<br/>(Gateway)
    participant DBA as MySQL-A<br/>(issue_request)
    participant Cache as Redis<br/>(coupon:available · negative cache)
    participant B as Server B<br/>(Accept/Cache)
    participant R as Redis<br/>(issue:pending HASH+ZSET)
    participant K as Kafka
    participant C as Server C<br/>(Persistence)
    participant DBC as MySQL-C

    User->>A: POST /api/v1/coupons/issue-request<br/>(X-User-Id, body)
    A->>A: 헤더 인증 + body validation

    Note over A,Cache: ADR-011 진입 단락 — 매진이면 B/C 까지 가지 않음
    A->>Cache: EXISTS coupon:available:{eventId}:{couponTypeId}

    alt 매진 (cache HIT)
        Cache-->>A: 1
        A->>DBA: INSERT issue_request (status=SOLD_OUT)
        A-->>User: 200 { status: SOLD_OUT }
    else 발급 가능 (cache MISS)
        Cache-->>A: 0
        A->>+B: POST /internal/v1/coupons/issue<br/>(sync · Resilience4j Circuit Breaker + Retry)

        B->>R: HSETNX issue:pending:{userId}:{couponTypeId}
        alt 이미 존재 (1 인 1 장 자연 차단)
            R-->>B: 0
            B-->>A: 200 { status: DUPLICATE }
        else 신규
            R-->>B: 1
            B->>R: HSET status=PENDING, publishAttempts=1, ...<br/>+ ZADD issue:pending:zset
            B->>K: publish coupon-issue-request<br/>(acks=all · idempotent producer)
            Note over B: publish 실패도 swallow → ACCEPTED<br/>(스케줄러가 30s 안에 회복 — ADR-008)
            B-->>-A: 200 { status: ACCEPTED }
        end

        A->>DBA: INSERT issue_request (status=ACCEPTED/DUPLICATE)
        A-->>User: 200 { requestId, status }
    end

    Note over K,DBC: ─── 이하 비동기 ───

    K->>+C: poll coupon-issue-request<br/>(max.poll.records=10 · concurrency=1)
    Note over C,DBC: 1 트랜잭션 (UNIQUE 멱등 + 비관적 락)
    C->>DBC: existsByUserIdAndCouponTypeId<br/>(early return on duplicate)
    C->>DBC: SELECT event — 유효성 (started_at ≤ now ≤ ended_at)
    C->>DBC: SELECT ... FOR UPDATE coupon_type_inventory<br/>+ decrement
    C->>DBC: INSERT user_coupon (SUCCESS / SOLD_OUT / FAILED)
    C->>DBC: INSERT outbox_event (status=PENDING)
    C->>DBC: COMMIT
    Note over C,Cache: afterCommit hook (ADR-011)
    C->>DBC: SELECT availableCount (fresh read)
    alt availableCount == 0
        C->>Cache: SET coupon:available:{e}:{c} 1 EX 86400
    end
    deactivate C

    Note over C,K: Outbox poller (별도 스레드, 트랜잭션 밖)
    C->>K: publish coupon-issue-result

    K->>B: poll coupon-issue-result
    B->>R: HSET issue:pending status=SUCCESS/...<br/>+ ZREM issue:pending:zset
```

> 다이어그램 비고
> - `coupon:available` 키 존재 = 매진 (값 의미 없음). C 가 SET, A 가 GET — Server B 까지 도달하지 않음.
> - HASH 와 ZSET 은 같은 prefix `issue:pending` 을 쓰지만 역할 분리 — HASH = 상세 (사용자 폴링용), ZSET = 미완료 work queue.
> - Outbox 의 `status=PENDING` 은 "Kafka 로 안 보낸 row" 의 메타-상태이며 발급 결과(payload.status)와 별개.

---

## 2. 쿠폰 사용 — `POST /api/v1/coupons/{code}/redeem`

Server C 단일 트랜잭션. `@Version` **낙관적 락** 으로 동시 호출 1 명만 성공 (ADR-007). 다른 사용자 자원은 404 로 마스킹.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant C as Server C<br/>(RedeemCouponService)
    participant DBC as MySQL-C<br/>(user_coupon · @Version)

    User->>C: POST /api/v1/coupons/{code}/redeem<br/>(X-User-Id)
    C->>C: 헤더 인증
    C->>DBC: SELECT user_coupon WHERE code = ?

    alt code 미존재
        DBC-->>C: empty
        C-->>User: 404 NOT_FOUND
    else 다른 사용자 소유
        DBC-->>C: row (user_id ≠ requester)
        Note over C: ownership masking — 존재 정보 노출 방지
        C-->>User: 404 NOT_FOUND
    else 이미 사용됨 (status=USED)
        DBC-->>C: row (used_at ≠ null)
        Note over C: 멱등 replay — 같은 user 재호출
        C-->>User: 200 { code, redeemedAt, newlyRedeemed: false }
    else 비-SUCCESS 상태 (SOLD_OUT/FAILED)
        DBC-->>C: row (status ≠ SUCCESS)
        C-->>User: 409 INVALID_STATE
    else 사용 가능 (SUCCESS, used_at=null, version=v)
        DBC-->>C: row
        C->>C: domain.markUsed(now) → status=USED
        C->>DBC: UPDATE user_coupon<br/>SET status=USED, used_at=?, version=v+1<br/>WHERE id=? AND version=v
        alt 0 rows (낙관락 충돌)
            DBC-->>C: 0
            C-->>User: 409 RACE_RETRY
        else 1 row
            DBC-->>C: 1
            C-->>User: 200 { code, redeemedAt, newlyRedeemed: true }
        end
    end
```

---

## 3. 내 쿠폰 목록 조회 — `GET /api/v1/users/me/coupons`

`/me` 패턴 — `X-User-Id` 헤더가 사용자를 결정 (path 에 userId 두지 않음). `idx_user_coupon_user` 인덱스 단건 쿼리. 페이지네이션 없음 (사용자당 ≤ 100).

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant C as Server C<br/>(UserCouponController)
    participant Svc as UserCouponQueryService<br/>(@Transactional readOnly)
    participant DBC as MySQL-C<br/>(user_coupon · idx_user_id)

    User->>C: GET /api/v1/users/me/coupons<br/>(X-User-Id 필수)

    alt X-User-Id 누락
        C-->>User: 400 MISSING_HEADER
    else 정상
        C->>Svc: findAllByUserId(userId)
        Svc->>DBC: SELECT user_coupon<br/>WHERE user_id = ?<br/>ORDER BY issued_at DESC
        DBC-->>Svc: list (빈 배열 가능)
        Svc-->>C: List<UserCouponJpaEntity>
        C->>C: entity → UserCouponResponse 매핑
        C-->>User: 200 { data: [...] }
    end

    Note over C,DBC: 응답 element 의 status<br/>SUCCESS / SOLD_OUT / FAILED / USED
```

---

## 4. 이벤트 조회 — `GET /api/v1/events/{eventId}`

Cache-Aside + **Refresh-Ahead** 의 2 단 방어로 stampede 회피 (평가 항목 ③).

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant C as Server C<br/>(EventQueryService)
    participant R as Redis<br/>(event:{id} · TTL 300s)
    participant DBC as MySQL-C<br/>(event)
    participant Sch as @Scheduled<br/>(EventCacheRefresher · 60s)

    User->>C: GET /api/v1/events/{eventId}
    C->>R: GET event:{eventId}

    alt 캐시 HIT (정상 경로)
        R-->>C: event payload (json)
        C-->>User: 200 { event }
    else 캐시 MISS (Redis blip 등 비정상)
        R-->>C: nil
        C->>DBC: SELECT event WHERE id = ?
        alt 존재
            DBC-->>C: row
            C->>R: SET event:{id} (TTL 300s)
            C-->>User: 200 { event }
        else 없음
            DBC-->>C: empty
            C-->>User: 404 EVENT_NOT_FOUND
        end
    end

    Note over Sch,R: Refresh-Ahead — TTL 만료 자체 회피<br/>(refresh 60s ≪ TTL 300s)
    loop 60s fixedDelay
        Sch->>DBC: SELECT * FROM event WHERE status = IN_PROGRESS
        DBC-->>Sch: list
        Sch->>R: SET event:{id} (TTL 300s) for each
        Note over Sch: 캐시는 항상 240s+ 잔여 TTL 유지
    end
```

---

## 5. 발급 보완 스케줄러 (B 의 `@Scheduled`)

Server B 의 `PendingIssueScheduler` — 결과 메시지를 10초 안에 못 받은 신청을 회복. cutoff(10s) × maxAttempts(3) = **30s SLA** (ADR-008).

```mermaid
sequenceDiagram
    autonumber
    participant Sch as @Scheduled<br/>(PendingIssueScheduler · 1s)
    participant R as Redis<br/>(issue:pending:zset/HASH)
    participant C as Server C<br/>(internal API)
    participant DBC as MySQL-C<br/>(user_coupon)
    participant K as Kafka<br/>(coupon-issue-request)

    loop 1s fixedDelay
        Sch->>R: ZRANGEBYSCORE issue:pending:zset<br/>0 (now - 10s) LIMIT 50
        R-->>Sch: stale members
        Note over Sch: 정상 처리된 항목은 result consumer 가 ZREM<br/>→ stale 결과에 포함되지 않음

        loop 각 stale 항목
            Sch->>+C: GET /internal/v1/users/{u}/coupons/{ct}
            C->>DBC: SELECT user_coupon
            DBC-->>C: row or empty
            C-->>-Sch: 200 (with status) / 404

            alt C 에 row 존재 (Mode B 회복 — result 메시지 유실)
                Sch->>R: HSET status=SUCCESS/...<br/>+ ZREM (종결)
            else 없음 + publishAttempts < 3
                Note over Sch,K: Mode A 회복 — Kafka 메시지 자체가 안 닿음
                Sch->>R: HINCRBY publishAttempts (cap 보장)<br/>+ ZADD score=now (grace)
                Sch->>K: republish coupon-issue-request<br/>(payload reconstructed from HASH)
            else 없음 + publishAttempts ≥ 3
                Note over Sch: 30s SLA 도달 — 영구 마감
                Sch->>R: HSET status=FAILED + ZREM
            end
        end
    end
```

> 핵심 보장
> - **카운터를 publish 직전 INCR** → 영구 publish 장애에서도 cap 이 항상 수렴 (무한 cycle 방지)
> - C 의 `(user_id, coupon_type_id)` UNIQUE 가 중복 publish 의 안전성 보호
> - lookup 자체 실패 시 catch + log → 다음 cycle 재시도 (zset 그대로 둠)

---

## 6. Outbox Poller (C 의 `@Scheduled`)

Server C 의 `OutboxPoller` — 트랜잭션 안전성을 위해 발급 결과를 Kafka 로 분리 publish (ADR-002).

poller 자체에는 `@Transactional` 을 걸지 않는다. 걸면 batch (기본 50) 만큼의 Kafka publish 왕복이 하나의 DB 트랜잭션 안에 들어가, 같은 MySQL-C 에서 재고 행에 비관적 락을 잡는 발급 트랜잭션과 커넥션을 두고 경합한다 (CLAUDE.md §10 — 트랜잭션 안 외부 호출). 조회 / 발행 / 상태 갱신을 세 단계로 나눈다.

```mermaid
sequenceDiagram
    autonumber
    participant Poll as OutboxPoller<br/>(@Scheduled · 500ms · 트랜잭션 없음)
    participant DBC as MySQL-C<br/>(outbox_event)
    participant K as Kafka<br/>(coupon-issue-result)

    loop 500ms fixedDelay
        Poll->>DBC: SELECT * FROM outbox_event<br/>WHERE status = PENDING<br/>ORDER BY created_at LIMIT 50<br/>(repository 의 짧은 read 트랜잭션)
        DBC-->>Poll: rows

        Note over Poll: outbox.status = PENDING<br/>→ "아직 Kafka 로 안 보낸 row" (메타-상태)<br/>(SUCCESS/SOLD_OUT/FAILED 모두 INSERT 직후 PENDING)

        loop 각 row (트랜잭션 밖)
            Poll->>K: publish coupon-issue-result<br/>(payload = json)
            alt 성공
                Note over Poll: id 를 published 목록에 모음
            else 실패
                Note over Poll: status 유지 — 다음 주기 재시도<br/>(at-least-once)
            end
        end

        Poll->>DBC: UPDATE outbox_event SET status=PUBLISHED, published_at=now<br/>WHERE outbox_event_id IN (:ids)<br/>(단일 문장 — 짧은 write 트랜잭션)
    end
```

> 중복 publish 방지: 발행에 성공한 row 의 status 를 cycle 끝에서 PUBLISHED 로 갱신해 다음 cycle 에서 재선택 안 됨. publish 는 됐는데 상태 갱신 직전 죽으면 다음 cycle 이 한 번 더 publish — at-least-once. B 의 result consumer 는 동일 메시지를 멱등 처리 (status 덮어쓰기는 idempotent).
