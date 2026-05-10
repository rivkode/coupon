# 다이어그램

## 📚 문서 목록

- [요구사항 분석](requirements.md)
- [시스템 아키텍처](architecture.md)
- **다이어그램** ← 현재 문서
  - [시퀀스 다이어그램](diagram-sequence.md)
  - [상태 다이어그램](diagram-state.md)
- [ERD](erd.md)
- [API 명세](api-spec.md)

[← README](../../README.md)

---

## 시퀀스 다이어그램 — 시간축 흐름

[시퀀스 다이어그램 →](diagram-sequence.md)

| # | 흐름 | 서버 | 핵심 |
|---|---|---|---|
| 1 | 쿠폰 발급 요청 (`POST /api/v1/coupons/issue-request`) | A → B → C | ADR-011 캐시 단락 + ADR-001 즉시 응답 + 비동기 처리 |
| 2 | 쿠폰 사용 (`POST /api/v1/coupons/{code}/redeem`) | C | ADR-007 낙관적 락 + ownership masking |
| 3 | 내 쿠폰 목록 조회 (`GET /api/v1/users/me/coupons`) | C | `/me` 패턴 + 인덱스 단건 쿼리 |
| 4 | 이벤트 조회 (`GET /api/v1/events/{eventId}`) | C | Cache-Aside + Refresh-Ahead (③ Hot Spot) |
| 5 | 발급 보완 스케줄러 | B → C | ADR-008 cap 재발행 + 30s SLA |
| 6 | Outbox Poller | C | ADR-002 at-least-once publish |

---

## 상태 다이어그램 — 도메인 전이

[상태 다이어그램 →](diagram-state.md)

| # | 도메인 | enum | 의미 |
|---|---|---|---|
| 1 | UserCoupon | `UserCouponStatus` | 발급 결과 + 사용 (`SUCCESS / SOLD_OUT / FAILED / USED`) |
| 2 | Event | `EventStatus` | 이벤트 lifecycle (`CREATED / IN_PROGRESS / ENDED / CANCELLED`) |
| 3 | IssueRequest (A audit) | `IssueRequestStatus` | A 의 진입 audit (`ACCEPTED / DUPLICATE / SOLD_OUT / REJECTED`) |
| 4 | PendingIssue (B Redis) | `IssuePendingStatus` | B 의 사용자 신청 lifecycle (`PENDING / SUCCESS / SOLD_OUT / FAILED`) |
