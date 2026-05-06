---
name: code-planning
description: 본 과제(선착순 쿠폰)에서 새 기능 구현, 버그 수정, 리팩토링 등 코드 작업을 시작할 때 먼저 사용하는 스킬. 요구사항 재진술, 영향 범위(Server A/B/C 어느 모듈), API 설계, TodoList 작성, 사용자 승인을 포함한다. "구현해줘", "추가해줘", "만들어줘", "고쳐줘", "리팩토링" 등 코드를 건드리는 모든 요청에서 먼저 이 스킬을 거친 뒤 다른 스킬로 이동한다. 계획 없이 바로 코드를 작성하지 않도록 반드시 사용.
---

# Code Planning — 요구사항 분석 및 계획 검증 (promotion)

본 스킬은 **모든 코드 작업의 진입점**. 코드를 한 줄이라도 작성하기 전에 본 단계를 완료한다.

> 본 과제는 CLAUDE.md 가 이미 도메인/아키텍처/ADR 을 결정해 두었음. 본 스킬은 **CLAUDE.md 위에서** 작은 작업 단위의 계획을 만든다.

---

## 1. 이 단계의 목표

- 요구사항 모호함 제거
- **CLAUDE.md 의 ADR / 안티패턴 (§6, §10) 과 충돌 여부 확인**
- 영향 모듈 식별 (`server-a` / `server-b` / `server-c` / `common`)
- 작업 순서 (TodoList) 확정
- 사용자의 명시적 승인

---

## 2. 실행 순서

### Step 0. CLAUDE.md 확인

작업이 CLAUDE.md §6 ADR 또는 §10 안티패턴과 충돌하는가? 충돌 시 사용자에게 먼저 확인.

예: "기능 X 를 위해 RDBMS row lock 으로 재고를 관리해야 하나요?" → CLAUDE.md §10 위반. 다른 방법(Lua) 으로 재설계 필요.

### Step 1. 요구사항 재진술 (Restate)

```
[요구사항 재진술]
사용자 요청: "쿠폰 사용 (redeem) 에 만료일 검증을 추가해줘"

내 이해:
- Coupon 에 expires_at 필드 추가
- redeem() 호출 시 현재 시각 > expires_at 이면 거절
- 만료된 쿠폰은 410 Gone (이미 사용된 것과 다른 응답)

확인이 필요한 점:
1. 만료일은 발급 시점에 정해지는가, 이벤트별 고정인가?
2. 부분 만료 (예: 30일 후) — 기본값과 override?
```

**불명확한 점이 2개 이상이면 멈추고 사용자에게 질문**.

### Step 2. 영향 모듈 식별

| 항목 | 파일 |
|---|---|
| Server A | (영향 없음) |
| Server B | (영향 없음 — Outbox payload 에 expires_at 추가 정도) |
| Server C | `Coupon` (도메인) + `CouponJpaEntity` (Infrastructure) + Mapper, `CouponRedeemService`, redemption controller, Flyway 마이그레이션 |
| common | (선택) Idempotency-Key 헤더 spec 변경 시 |
| load-test | redeem 시나리오 추가 (있다면) |

CLAUDE.md §5 (서비스 책임) 와 충돌 없는지 확인.

### Step 3. API 설계 (해당 시)

```
POST /api/v1/coupons/{code}/redeem
Headers:
  X-User-Id: <user_id>
  Idempotency-Key: <uuid>

Response:
  200 OK              { code, redeemedAt, ... }
  404 NOT_FOUND       쿠폰 없음
  409 CONFLICT        이미 사용됨 (낙관락 실패도 포함)
  410 GONE            만료됨
  401 UNAUTHORIZED    소유자 아님
  429 RATE_LIMIT      bucket 초과
  503 SERVICE_DOWN    Circuit Breaker OPEN
```

### Step 4. TodoList 작성

본 과제는 **레이어드 + 가벼운 DDD** (CLAUDE.md §11). Domain → Application → Infrastructure → Presentation 순.

```
[ ] 1. Coupon (도메인 객체): expires_at 필드 + redeem() 에 만료 검증
[ ] 1a. CouponJpaEntity (Infrastructure): expires_at 컬럼 매핑
[ ] 1b. CouponMapper: Domain ↔ JpaEntity 양방향 변환 갱신
[ ] 2. CouponExpiredException (도메인 예외)
[ ] 3. Domain 단위 테스트 — 만료 케이스
[ ] 4. CouponRedeemService 의 예외 처리 추가
[ ] 5. Application 단위 테스트
[ ] 6. Flyway 마이그레이션 — expires_at 컬럼
[ ] 7. Controller / GlobalExceptionHandler 의 410 매핑
[ ] 8. Presentation 슬라이스 테스트 (`@WebMvcTest`)
[ ] 9. 통합 테스트 (`@SpringBootTest`) — 만료/정상 redeem
[ ] 10. (선택) load-test/scenarios/redeem.js 시나리오
[ ] 11. README API §4 갱신
[ ] 12. 커밋 + PR
```

### Step 5. 사용자 승인 요청

Step 1~4 결과를 한 번에 보여주고 명시적 확인.

```
위 계획대로 진행해도 될까요?
수정이 필요한 부분이나 빠진 요구사항이 있다면 알려주세요.
```

승인 없이 Step 6 (구현) 으로 넘어가지 않는다.

---

## 3. 자주 쓰는 질문 패턴

- **경계 조건**: "X 가 0/null 일 때 처리?"
- **동시성**: "동시 변경 가능성? 락 전략은?" → `concurrency/SKILL.md`
- **트랜잭션 경계**: "A 실패 시 B 롤백?" → CLAUDE.md ADR-001 / `system-design/SKILL.md`
- **하위 호환**: "기존 클라이언트 영향? Breaking 허용?"
- **성능**: "예상 트래픽? p95 SLA?" → `k6-load-testing/SKILL.md`
- **권한**: "호출 가능 주체?"

---

## 4. 자가 검증 체크리스트 (Step 5 전에)

- [ ] CLAUDE.md §6 ADR / §10 안티패턴과 충돌 없는가?
- [ ] 요구사항을 내 말로 정리?
- [ ] 불명확한 점을 모두 질문 또는 명시적 가정으로 적었는가?
- [ ] 영향 모듈 (`server-a/b/c/common/load-test`) 을 식별?
- [ ] API 변경이 있다면 시그니처, 에러 케이스, HTTP 코드 매핑 포함?
- [ ] TodoList 가 **도메인 → 애플리케이션 → 인프라 → 프레젠테이션** 순?
- [ ] DB 스키마 변경이 있다면 Flyway 마이그레이션 항목 포함?
- [ ] 테스트 항목이 계층별로 분리?
- [ ] CLAUDE.md §3 5축 (트래픽/정합성/캐시/Rate Limit/사이징) 중 어디에 영향?

---

## 5. 안티 패턴 (하지 말 것)

1. **"일단 만들고 나서 물어보자"** — 돌이키기 어렵다.
2. **CLAUDE.md 무시한 즉흥 결정** — ADR 와 충돌하면 사용자에게 먼저 확인.
3. **Controller 부터 작성** — 도메인이 흔들리면 전부 다시 짜야 한다.
4. **추측 (assume)** — 요구사항 임의 확장의 가장 흔한 실수.
5. **TodoList 없이 진행** — scope creep.
6. **DB 스키마부터 설계** — 도메인이 DB 에 종속.
7. **`scope-discipline` 무시** — 5일 일정이라는 사실을 잊고 nice-to-have 폭주.

---

## 6. 다음 단계

계획이 승인되면:
- 5축 평가 항목 매핑에 따라:
  - ① 트래픽/동시성 → `concurrency/SKILL.md`
  - ② 정합성/멱등성 → `system-design/SKILL.md`
  - ③ 캐시/Hot Spot → `cache-strategy/SKILL.md`
  - ④ Rate Limit → `rate-limiting-backpressure/SKILL.md`
  - ⑤ 사이징 → `k6-load-testing/SKILL.md` + `capacity-planning/SKILL.md`
- 코드를 만들기 직전 매번 → `scope-discipline/SKILL.md` 결정 트리 통과
- 구현 완료 후 → `code-reviewer` agent 호출
