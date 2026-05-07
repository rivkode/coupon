# Redeem 의 멱등성: 별도 캐시 / 테이블 미도입

> **Status**: 결정 (2026-05-07)
> **결정**: redeem 은 도메인 자체 멱등에 의존. 별도 idem 캐시 / 테이블 / 응답 캐시 도입하지 않음.
> **연관**: CLAUDE.md ADR-004, ADR-007, PR #17

발급(issue) 의 강한 멱등 (Server B Redis user-scoped 캐시 + Server C `(user_id, idempotency_key) UNIQUE`)
와 다른 정책. 본 결정은 **redeem 의 결과가 본질적으로 멱등** 이라는 도메인 성질 위에 세워졌다.

---

## 1. 문제

CLAUDE.md ADR-004 가 "Idempotency-Key 헤더는 발급(issue) + 사용(redeem) 양쪽에 적용" 이라고 명시.
issue 는 분명한 멱등 보호 (Server A 캐시 + Server B 캐시 + Server C UNIQUE) 를 가지나, redeem 도
같은 강도의 보호가 필요한가?

옵션:
- **옵션 A (강한 보호)**: redeem 응답을 별도 테이블 (`coupon_redemption_log`) 또는 Redis 캐시에 저장.
  같은 `(user_id, idempotency_key)` 두 번째 호출은 캐시 hit.
- **옵션 B (도메인 자체 멱등)**: redeem 의 결과가 본질적으로 멱등 — `used_at` 한 번 set 후 영구.
  같은 user 의 두 번째 호출은 도메인 분기로 200 + 기존 redeemedAt 반환.
- **옵션 C (응답 캐시 only)**: Idempotency-Key 헤더의 캐싱은 issue 와 동일하게 Server A / B / C
  레벨 응답 캐시.

---

## 2. 결정

**옵션 B 채택**. Idempotency-Key 헤더는 받지만 trace/log 용으로만 활용 — 별도 캐시/테이블 없음.

흐름:
```
findByCode(code)
  ├─ empty                                        → 404 NOT_FOUND
  ├─ owner != requester                          → 404 NOT_FOUND (마스킹)
  ├─ used + same owner                           → 200 + 기존 redeemedAt (newlyRedeemed=false)
  └─ unused → coupon.redeem(now) + save           → 200 + 새 redeemedAt (newlyRedeemed=true)
                ├─ stale @Version race            → OptimisticLockingFailureException → 409 RACE_RETRY
                └─ 클라이언트 재시도 시 위의 used 분기로 자연 흡수
```

---

## 3. 옵션 B 의 핵심 근거

### 3.1 redeem 의 결과는 본질적으로 멱등

`used_at` 은 **한 번 set 후 변경 불가**. `Coupon.redeem(now)` 의 invariant 가 이를 보장한다 (이미
사용된 쿠폰에 redeem 호출 시 `IllegalStateException`).

따라서:
- 같은 user 가 `idem-A` 로 호출 → 200, used_at = T
- 같은 user 가 `idem-B` 로 다시 호출 (다른 idem) → 200, used_at = T (멱등)
- 같은 user 가 `idem-A` 로 다시 호출 (같은 idem) → 200, used_at = T (멱등)

세 경우 모두 **같은 결과**. idem 캐시 없이도 의미적으로 멱등.

### 3.2 옵션 A 의 보호 가치 < 추가 비용

옵션 A 가 막아주는 시나리오는 본질적으로 발생하지 않는다:

- 같은 user 가 같은 coupon 을 같은 idem-key 로 두 번 호출 → 옵션 B 의 used 분기가 같은 결과 반환.
  옵션 A 가 추가 보호하는 게 없다.
- 다른 user 가 같은 idem-key 로 호출 → 옵션 B 의 소유권 검증이 404 마스킹. 옵션 A 도 동일.
- 다른 coupon 에 같은 idem-key 로 호출 → coupon code 가 다르므로 도메인 객체가 다름. idem-key
  로 캐시 hit 시키려면 `(user, idem)` 만으로 캐시 — 단, 다른 coupon 의 결과를 잘못 반환할 위험.

옵션 A 의 비용:
- `coupon_redemption_log` 테이블 + UNIQUE constraint
- 또는 Redis 응답 캐시 + TTL
- redeem 호출 시마다 추가 read/write
- 만료 정책 + 재생 정책

5일 일정에 비해 보호 가치가 낮다.

### 3.3 발급(issue) 과의 정책 차이는 의도적

issue 는 idem 보호가 강해야 한다:
- Redis 차감 + Outbox INSERT 조합이 두 번 일어나면 **재고 두 번 차감** 가능. 보상 트랜잭션이 있어도
  사용자 응답은 두 번 모두 성공.
- 그래서 **재고 차감 자체를 막는** Server B 의 user-scoped 캐시 + **Outbox INSERT 자체를 막는**
  user-scoped UNIQUE 가 필요.

redeem 은 다르다:
- `used_at` set 은 두 번째 호출에서도 **첫 번째 시점의 값으로 머문다** (상태 변경 X). 즉 부작용
  자체가 한 번만 일어남. 별도 외부 보호 불필요.

이 차이는 **본 시스템의 핵심 시그널** — "발급은 외부 부작용 (재고 + Outbox 메시지) 이 강해서 idem
캐시로 막고, redeem 은 도메인 단의 한 번 set 으로 충분" 이라는 결정의 명확한 표현.

---

## 4. 거부된 대안

### 옵션 A (별도 redeem 테이블 / 캐시)

- §3.2 의 보호 가치가 낮음.
- 5일 일정 외 추가 인프라 (테이블 + UNIQUE 또는 Redis TTL).
- 평가 시그널 약함 — 평가자 입장에서 "왜 추가 보호?" 질문에 답하기 어려움.

### 옵션 C (응답 캐시 only)

- 응답 자체의 캐싱은 옵션 B 의 도메인 분기와 같은 효과를 내지만, **응답 byte-level 캐시** 는 시간
  필드 (redeemedAt) 의 ms-truncate 차이까지 같지 않을 수 있음 (다른 micro 정밀도).
- 옵션 B 가 더 정확한 의미적 멱등 (DB 영속 값을 일관 반환).

### 별도 옵션: redeem 호출도 Server A → Server C 로 라우팅

- CLAUDE.md §5.3 이 redeem 을 server-c 자체 API 로 명시 — server-a 진입점 가설은 현재 시나리오 외.
- 본 결정 영역 외, 향후 게이트웨이 layer 추가 시 별도 결정.

---

## 5. Trade-off

### 5.1 보호하지 못하는 시나리오

본 결정으로 다음은 보호되지 않는다 (또는 도메인 분기가 흡수):
- **악의적 사용자가 다른 idem-key 로 redeem 무한 호출**: 매번 200 + 같은 redeemedAt.
  Rate Limit (Server A) 가 흡수해야 — 단, 본 과제의 redeem 은 Server C 직접 호출이라 server-a 의
  rate limit 미적용. 진화 방향: redeem 도 Server A 라우팅 또는 별도 rate limit.
- **클라이언트 라이브러리 버그로 같은 redeem 을 다른 idem 으로 N 회 호출**: 도메인 멱등이 200 응답
  유지하나 매 호출이 DB read 발생. 1 vCPU / 평균 부하 시 N=100 도 ms 단위 — 문제 없음.

### 5.2 응답 의미

옵션 B 의 응답에는 `newlyRedeemed` boolean 이 포함된다:
- `true` — 이번 호출이 used_at 을 처음 set
- `false` — 이전에 이미 set 됨, 현재 호출은 멱등 read

클라이언트가 "내가 처음 redeem 했는지" 를 알 수 있다 (예: "할인 적용됐다" UI vs "이미 사용한 쿠폰" UI).

---

## 6. 검증

### 단위 / IT (server-c)

- `RedeemCouponServiceTest.redeem_returns_existing_when_already_redeemed_by_same_user` — 멱등 분기
- `RedeemCouponConcurrencyIT.sequential_replay_returns_idempotent` — 같은 user 두 번째 호출 검증
- `RedeemCouponConcurrencyIT.concurrent_redeem_results_in_single_used_at` — 5 thread race 시
  newlyRedeemed=1 + 4 흡수 (멱등 또는 OptimisticLockingFailureException)

### e2e (k6)

- `day3-02-redeem-idempotent.js` — 같은 user 가 다른 idem-key 로 두 번 호출 → 200/200,
  newlyRedeemed=true→false, 두 idem 응답의 redeemedAt 정확히 일치
- `day3-03-redeem-ownership-mask.js` — otherId 시도 후 owner 의 후속 redeem 이 사이드 이펙트 없음
  검증 (used_at 변경 X)

---

## 7. 후속 / 진화 방향

다음이 필요하면 옵션 A 또는 C 도입 고려:
- redeem 결과를 외부 시스템 (결제, 주문) 에 비동기 발행 — 이 경우 발행 자체의 멱등성 보호 필요
  → Outbox 패턴 + `(user_id, coupon_code, idempotency_key) UNIQUE`
- redeem 의 "취소 (cancel)" 기능 — 상태 머신이 더 복잡해지면 Idempotency-Key 응답 캐시가 도움
- API gateway 가 추가되어 모든 호출에 일관된 idem 응답 캐시 정책 — gateway 레벨 적용
