# API 명세

## 📚 문서 목록

- [요구사항 분석](requirements.md)
- [시스템 아키텍처](architecture.md)
- [다이어그램](diagrams.md)
  - [시퀀스 다이어그램](diagram-sequence.md)
  - [상태 다이어그램](diagram-state.md)
- [ERD](erd.md)
- **API 명세** ← 현재 문서

[← README](../../README.md)

---

## 0. 공통 (Common)

### 0.1 인증

`X-User-Id` 헤더로 사용자를 식별. 운영 환경에서는 상위 게이트웨이 (API Gateway / OAuth2 Resource Server) 가 JWT 검증 후 user_id 를 추출해 헤더로 전달하는 모델을 가정 (CLAUDE.md §5).

```
X-User-Id: 123456
```

### 0.2 응답 봉투 (Response Envelope)

모든 응답은 다음 봉투를 사용 (`ApiResponse<T>`).

```json
{
  "success": true,
  "data": { ... }
}
```

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "request body validation failed",
    "fieldErrors": [ { "field": "eventId", "message": "must be positive" } ]
  }
}
```

`null` 필드는 직렬화에서 생략 (`@JsonInclude(NON_NULL)`).

### 0.3 표준 에러 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| `VALIDATION_FAILED` | 400 | bean validation 실패 (`fieldErrors` 채움) |
| `MISSING_HEADER` | 400 | 필수 헤더 누락 (예: `X-User-Id`) |
| `MALFORMED_BODY` | 400 | 본문 파싱 실패 (JSON 깨짐) |
| `TYPE_MISMATCH` | 400 | path / query 인자 타입 불일치 |
| `INVALID_ARGUMENT` | 400 | 도메인 인자 검증 실패 |
| `NOT_FOUND` | 404 | 쿠폰 미존재 / 소유권 마스킹 (Server C) |
| `EVENT_NOT_FOUND` | 404 | 이벤트 미존재 (Server C) |
| `INVALID_STATE` | 409 | 도메인 상태 전이 실패 (예: 이미 USED 인데 redeem) |
| `RACE_RETRY` | 409 | 낙관락 충돌 — 재시도 안내 |
| `INTERNAL_ERROR` | 500 | 마스킹된 서버 오류 |

> Server A 는 **503 + `Retry-After`** 도 발급 (Circuit Breaker OPEN / B 일시 장애). 본문은 비어 있지 않고 `IssueCouponResponse` 가 들어 있으나 호출자는 5 초 후 재시도하면 된다.

---

## 1. 사용자 API

### 1.1 쿠폰 발급 요청

쿠폰 발급을 접수하고 즉시 응답을 반환한다. 실제 발급(재고 차감) 은 Server C 에서 비동기 처리되므로, 결과는 폴링 / 내정보 조회로 비동기 확인 (ADR-001).

| 항목 | 값 |
|---|---|
| 메서드 / 경로 | `POST /api/v1/coupons/issue-request` |
| 서버 / 포트 | Server A (`:8080`) |
| 인증 | `X-User-Id` (필수) |

**요청 본문 (10 필드)**

| 필드 | 타입 | 필수 | 제약 / 의미 |
|---|---|---|---|
| `country` | string | ✅ | `@NotBlank`, max 8 (예: `KR`) |
| `eventId` | long | ✅ | `@Positive` |
| `couponTypeId` | long | ✅ | `@Positive` |
| `issuedAt` | ISO-8601 Instant | ✅ | — |
| `expireAt` | ISO-8601 Instant | ✅ | — |
| `channel` | string | ✅ | `@NotBlank`, max 32 (예: `APP`, `WEB`) |
| `deviceId` | string | ⛔️ | max 64 |
| `clientVersion` | string | ⛔️ | max 32 |
| `language` | string | ⛔️ | max 8 (예: `ko`) |
| `marketingConsent` | boolean | ⛔️ | — |

**요청 예시**

```http
POST /api/v1/coupons/issue-request HTTP/1.1
Host: localhost:8080
Content-Type: application/json
X-User-Id: 123456

{
  "country": "KR",
  "eventId": 202605,
  "couponTypeId": 1,
  "issuedAt": "2026-05-09T20:00:00Z",
  "expireAt": "2026-06-09T23:59:59Z",
  "channel": "APP",
  "deviceId": "abc-12345",
  "clientVersion": "3.2.1",
  "language": "ko",
  "marketingConsent": true
}
```

**성공 응답 (200 OK)**

`status` 가 `ACCEPTED` 또는 `DUPLICATE` 둘 다 200 으로 반환 (사용자 입장에선 결과는 폴링으로 확인).

```json
{
  "success": true,
  "data": {
    "requestId": "1f6b0c9a-1b2e-4f5a-9c20-3d7c1f8a90ef",
    "status": "ACCEPTED",
    "message": "issue request accepted"
  }
}
```

| `status` | 의미 |
|---|---|
| `ACCEPTED` | Redis 적재 + Kafka publish 성공. 결과는 폴링으로 확인 |
| `DUPLICATE` | 같은 `(user_id, coupon_type_id)` 가 이미 pending / 발급됨 (멱등) |

**실패 응답**

| HTTP | code | 시나리오 |
|---|---|---|
| 400 | `MISSING_HEADER` | `X-User-Id` 누락 |
| 400 | `VALIDATION_FAILED` | body 필드 검증 실패 (`fieldErrors` 포함) |
| 400 | `MALFORMED_BODY` | JSON 파싱 실패 |
| **503** | (envelope `success: true`, `status: INTERNAL_ERROR`) | Circuit Breaker OPEN / B 일시 장애. **`Retry-After: 5`** 헤더 동봉 |

```http
HTTP/1.1 503 Service Unavailable
Retry-After: 5

{
  "success": true,
  "data": {
    "requestId": "1f6b0c9a-...",
    "status": "INTERNAL_ERROR",
    "message": "downstream temporarily unavailable"
  }
}
```

---

### 1.2 쿠폰 사용 (Redeem)

발급받은 쿠폰을 사용 처리한다. `@Version` 낙관적 락으로 동시 호출 1 명만 성공 (ADR-007).

| 항목 | 값 |
|---|---|
| 메서드 / 경로 | `POST /api/v1/coupons/{code}/redeem` |
| 서버 / 포트 | Server C (`:8082`) |
| 인증 | `X-User-Id` (필수) |

**Path 파라미터**

| 이름 | 타입 | 의미 |
|---|---|---|
| `code` | string | 발급된 쿠폰 코드 (`user_coupon.code`, UNIQUE) |

**요청 예시**

```http
POST /api/v1/coupons/CPN-7F3A2B/redeem HTTP/1.1
Host: localhost:8082
X-User-Id: 123456
```

**성공 응답 (200 OK)**

```json
{
  "success": true,
  "data": {
    "code": "CPN-7F3A2B",
    "userId": 123456,
    "redeemedAt": "2026-05-10T14:23:11.123",
    "newlyRedeemed": true
  }
}
```

| 필드 | 의미 |
|---|---|
| `newlyRedeemed: true` | 본 호출에서 USED 로 전이 |
| `newlyRedeemed: false` | 같은 user 의 멱등 replay (이미 USED). `redeemedAt` 은 최초 사용 시각 그대로 |

**실패 응답**

| HTTP | code | 시나리오 |
|---|---|---|
| 400 | `MISSING_HEADER` | `X-User-Id` 누락 |
| 400 | `INVALID_ARGUMENT` | code 형식 위반 (도메인 인자 검증) |
| **404** | `NOT_FOUND` | code 미존재 **또는 다른 user 소유** (ownership masking) |
| **409** | `INVALID_STATE` | 사용 불가 상태 (예: 발급 자체가 SOLD_OUT / FAILED) |
| **409** | `RACE_RETRY` | 낙관락 충돌 — 동시 호출 다른 쪽이 선점. 즉시 재시도 가능 |
| 500 | `INTERNAL_ERROR` | 서버 오류 |

---

### 1.3 내 쿠폰 목록 조회

사용자 본인이 발급받은 쿠폰 목록을 반환. `/me` 패턴 — path 에 `userId` 를 두지 않고 `X-User-Id` 헤더가 사용자를 결정 (GitHub `/users/me/...`, Microsoft Graph `/me/...` 관행).

| 항목 | 값 |
|---|---|
| 메서드 / 경로 | `GET /api/v1/users/me/coupons` |
| 서버 / 포트 | Server C (`:8082`) |
| 인증 | `X-User-Id` (필수) |

**요청 예시**

```http
GET /api/v1/users/me/coupons HTTP/1.1
Host: localhost:8082
X-User-Id: 123456
```

**성공 응답 (200 OK)**

배열 — `issued_at DESC` 정렬. 보유 쿠폰이 없으면 빈 배열.

```json
{
  "success": true,
  "data": [
    {
      "userId": 123456,
      "eventId": 202605,
      "couponTypeId": 1,
      "code": "CPN-7F3A2B",
      "status": "USED",
      "issuedAt": "2026-05-10T14:20:00.000",
      "usedAt": "2026-05-10T14:23:11.123"
    },
    {
      "userId": 123456,
      "eventId": 202604,
      "couponTypeId": 5,
      "code": "CPN-3D9E1A",
      "status": "SUCCESS",
      "issuedAt": "2026-04-15T10:00:00.000",
      "usedAt": null
    }
  ]
}
```

| 필드 | 의미 |
|---|---|
| `status` | `SUCCESS / SOLD_OUT / FAILED / USED` (`UserCouponStatus`) |
| `usedAt` | 미사용 시 `null` — "아직 안 썼다" 가 의미 있는 정보라 직렬화에서 생략하지 않음 (`@JsonInclude(NON_NULL)` 미적용) |

**비고**

- **페이지네이션 없음** — 사용자당 이벤트 상한 100 (CLAUDE.md §2) 이라 응답 크기 한계가 작음 (scope-discipline).
- 정렬: `issued_at DESC` (최근 발급 순).

**실패 응답**

| HTTP | code | 시나리오 |
|---|---|---|
| 400 | `MISSING_HEADER` | `X-User-Id` 누락 |
| 500 | `INTERNAL_ERROR` | 서버 오류 |

---

### 1.4 이벤트 조회

이벤트 정보를 반환. Cache-Aside (Redis) + `EventCacheRefresher` 의 Refresh-Ahead 로 stampede 방어 (평가 항목 ③).

| 항목 | 값 |
|---|---|
| 메서드 / 경로 | `GET /api/v1/events/{eventId}` |
| 서버 / 포트 | Server C (`:8082`) |
| 인증 | (선택) — `X-User-Id` 강제 안 함 |

**Path 파라미터**

| 이름 | 타입 | 의미 |
|---|---|---|
| `eventId` | long | 이벤트 식별자 |

**요청 예시**

```http
GET /api/v1/events/202605 HTTP/1.1
Host: localhost:8082
```

**성공 응답 (200 OK)**

```json
{
  "success": true,
  "data": {
    "eventId": 202605,
    "name": "5 월 봄맞이 할인 이벤트",
    "content": "전 상품 최대 30% 할인",
    "startedAt": "2026-05-01T00:00:00",
    "endedAt": "2026-05-31T23:59:59",
    "status": "IN_PROGRESS"
  }
}
```

`status` 는 `EventStatus` enum: `CREATED / IN_PROGRESS / ENDED / CANCELLED`.

**실패 응답**

| HTTP | code | 시나리오 |
|---|---|---|
| 400 | `TYPE_MISMATCH` | `eventId` 가 숫자가 아님 |
| 404 | `EVENT_NOT_FOUND` | 미존재 |

---

## 2. 내부 API (Internal — 참고)

서비스 간 통신용. 외부에서 직접 호출하지 않으나 운영 / 디버깅 / 통합 테스트에서 참조.

### 2.1 발급 접수 (A → B)

Server A 가 발급 요청을 위임하는 진입. Server B 가 Redis 적재 + Kafka publish 후 즉시 응답.

| 항목 | 값 |
|---|---|
| 메서드 / 경로 | `POST /internal/v1/coupons/issue` |
| 서버 / 포트 | Server B (`:8081`) |
| 호출자 | Server A (`RestClientCouponIssuingClient`) |
| 인증 | `X-User-Id` (필수, 헤더 일관성) |

**요청 본문**

```json
{ "eventId": 202605, "couponTypeId": 1 }
```

| 필드 | 타입 | 제약 |
|---|---|---|
| `eventId` | long | `@Positive` |
| `couponTypeId` | long | `@Positive` |

**성공 응답 (200 OK)** — envelope 없이 raw payload

```json
{
  "requestId": "1f6b0c9a-...",
  "status": "ACCEPTED",
  "message": "issue request accepted"
}
```

`status` ∈ `ACCEPTED / DUPLICATE / INTERNAL_ERROR` (`IssueAcceptanceStatus`).

> Server A 는 본 응답을 받아 자신의 envelope 으로 다시 감싸 사용자에게 전달.

---

### 2.2 사용자 쿠폰 단건 조회 (B 스케줄러 → C)

Server B 의 `@Scheduled` 가 10 초 이상 pending 인 신청에 대해 직접 호출 (ADR-008). Kafka 메시지 유실 / 지연 보완용.

| 항목 | 값 |
|---|---|
| 메서드 / 경로 | `GET /internal/v1/users/{userId}/coupons/{couponTypeId}` |
| 서버 / 포트 | Server C (`:8082`) |
| 호출자 | Server B (`PendingIssueScheduler`) |

**Path 파라미터**

| 이름 | 타입 | 의미 |
|---|---|---|
| `userId` | long | 사용자 ID |
| `couponTypeId` | long | 쿠폰 종류 ID |

**성공 응답 (200 OK)**

```json
{
  "success": true,
  "data": {
    "userId": 123456,
    "eventId": 202605,
    "couponTypeId": 1,
    "code": "CPN-7F3A2B",
    "status": "SUCCESS",
    "issuedAt": "2026-05-10T14:20:00.000"
  }
}
```

`status` ∈ `SUCCESS / SOLD_OUT / FAILED / USED` (`UserCouponStatus`).

**실패 응답**

| HTTP | code | 시나리오 |
|---|---|---|
| 404 | `NOT_FOUND` | 아직 처리 전 (Kafka 컨슘 대기) — 스케줄러는 다음 cycle 에 재시도 |

---

## 3. 포트 / 헬스체크 요약

| 서비스 | 포트 | 헬스 |
|---|---|---|
| Server A | `:8080` | `/actuator/health` |
| Server B | `:8081` | `/actuator/health` |
| Server C | `:8082` | `/actuator/health` |

| 인프라 | 포트 | 비고 |
|---|---|---|
| MySQL-A | `:3306` | schema `server_a` |
| MySQL-C | `:3307` | schema `server_c` |
| Redis | `:6379` | — |
| Kafka | `:9092` (INTERNAL) / `:29092` (HOST) | — |
