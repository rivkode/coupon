# Server B 의 idempotency 1차 캐시를 user-scoped 로 (ADR-004 일관 적용)

> **Status**: 결정 (2026-05-07)
> **결정**: server-b 의 Redis idem 캐시 키를 `coupon:idem:{idempotencyKey}` 에서
> **`coupon:idem:{userId}:{idempotencyKey}`** 로 변경.
> **연관**: CLAUDE.md ADR-004, §9 (Server A `(user_id, idempotency_key) UNIQUE`)
>
> 이 문서는 PR #11 의 phase9-03-cross-user 시나리오 정리 중 발견된 server-a/server-b 의
> idem 정책 비대칭을 해소한 결정 기록. README 통합 시 ADR-004 본문에 흡수 가능.

---

## 1. 문제

PR #10 시점의 server-b 는 idem 1차 캐시 키를 `coupon:idem:{idempotencyKey}` 로 정의했다.
즉 idem-only 키. 이는 server-a 의 `(user_id, idempotency_key) UNIQUE` (CLAUDE.md §9) 와
비대칭.

비대칭의 영향:

- 같은 idem 으로 두 user 가 호출하는 시나리오에서 server-a 는 두 user 모두 받음.
- 그러나 server-b 는 첫 호출 결과를 idem-only 캐시에 저장 → 두 번째 user 호출 시 캐시 hit
  → ALREADY_ISSUED + 첫 user 의 couponCode 반환.
- 결과: 두 user 가 같은 couponCode 를 받는다 (Server C UNIQUE constraint 가 결국 거부하지만,
  Outbox INSERT 시점에 충돌 발생 → 보상 트랜잭션 발동 가능성).

## 2. 의사결정

**ADR-004 의 의도**는 "**(user, idempotency-key) 조합** 이 같은 결과를 반환" — 같은 idem
이라도 user 가 다르면 서로 다른 발급. 이는 서로 다른 사용자가 우연히 같은 UUID 를 쓰는
상황의 안전망.

따라서 server-b 의 1차 캐시도 **user-scoped** 로:

```
coupon:idem:{userId}:{idempotencyKey} → couponCode (TTL 24h)
```

server-a 의 `IdempotencyFilter` 도 분산 캐시 키를 user-scoped 로 사용 (Day 1 PR #6),
Server C 의 DB UNIQUE constraint 도 `(user_id, idempotency_key)` 가 의미적 권위. **세
계층 모두 user-scoped 로 정합**.

## 3. 변경 범위

### 3.1 코드

| 파일 | 변경 |
|---|---|
| `server-b/.../infrastructure/redis/RedisKeys.java` | `idempotencyKey(String)` → `idempotencyKey(long, String)`, 키 패턴 `coupon:idem:{userId}:{key}` |
| `server-b/.../infrastructure/redis/RedisStockClient.java` | `tryIssue` / `compensate` 의 KEYS[3] 빌드 시 userId 전달 |
| `server-b/src/main/resources/redis/issue-coupon.lua` | **변경 없음** — KEYS 만 받으므로 호출자가 키를 만들어 주입 |
| `server-b/src/main/resources/redis/compensate.lua` | **변경 없음** — 동일 |
| `server-b/.../test/.../RedisStockClientIT.java` | 검증 시 `RedisKeys.idempotencyKey(userId, idem)` 사용 + 신규 `cross_user_same_idem_returns_separate_issued` IT |
| `load-test/scenarios/phase9-03-cross-user.js` | `couponCode 가 서로 다름` 기대 복원 |

### 3.2 문서

| 파일 | 변경 |
|---|---|
| `README.md` | "이중 → 삼중 멱등성 방어" 으로 보강 (Server A + Server B + Server C) |
| `README.md` | trade-off 섹션에서 "server-b idem 캐시 비대칭" 항목 제거 |

## 4. 거부된 대안

### A. server-a 의 user-scoped UNIQUE 를 idem-only 로 변경

ADR-004 의 user 별 격리 의도를 깬다. UUID 충돌 확률은 낮지만 0 이 아니고, 다른 user 가
실수로 같은 idem 을 보낼 수 있다 (예: 클라이언트 라이브러리 버그). 거부.

### B. 시나리오를 통합 환경 동작에 맞게 약화 (PR #11 의 임시 결정)

phase9-03 의 `couponCode 다름` 기대를 제거. 평가 시그널 약화 + ADR-004 의 한 측면을
검증 못 함. PR #11 에선 단기 수용했으나 본 PR 에서 정합성 회복.

## 5. TTL 영향

24h TTL 동안 사용자 별 (idem) 키가 더 많이 생성됨. 단, 사용자당 발급 시도가 짧은 시간에
N 회 일어나는 시나리오 (rate limit 10 req/sec) 에서도 메모리 영향 작음 — 한 idem 당
~ 50 byte (`coupon:idem:1234:uuid` + couponCode value) × 10 req/sec × 24h × N 사용자.
1,000 명 동시 사용자 × 100 req 이면 ~ 5 MB. Redis 2 GB 환경에서 무시 가능.

## 6. 후속 검토 (해당 시)

- **Lua 스크립트 자체에 userId 검증 추가 가능성**: 현재는 Java 측이 키를 만들어 주입.
  Lua 안에서 KEYS[3] 가 expected user 와 일치하는지 검증하면 더 강한 안전망. 단, KEYS 가
  expected pattern 을 따르는지 검증 자체가 trust boundary 외 — Java 측 컨트롤이라
  실용상 불필요. 현재로 유지.
- **server-c 의 redeem 시점에도 user-scoped idem**: redeem 도 ADR-004 적용 범위 (CLAUDE.md
  §6). Day 3 PR 에서 동일 정책 적용 확인 필요.
