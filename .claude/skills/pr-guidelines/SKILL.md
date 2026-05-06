---
name: pr-guidelines
description: Pull Request를 생성하거나 PR 설명을 작성할 때 사용하는 스킬. PR 제목 형식(Conventional Commits), PR 본문 템플릿, 브랜치 네이밍, PR 크기 기준, 셀프 리뷰, 머지 전략을 포함한다. "PR 올려줘", "pull request", "PR 만들어줘" 같은 언급이나 커밋 완료 후 푸시 단계에서 사용한다. 작고 셀프 리뷰 가능한 PR 유지가 원칙.
---

# Pull Request Guidelines - 프로모션 시스템

이 스킬은 **커밋이 완료된 후** PR 을 생성할 때 사용합니다.
이 프로젝트는 **솔로 개발자가 진행하는 5일짜리 채용 과제** 입니다. PR 워크플로우는
팀 협업이 아닌 **자기 규율 + 평가자에게 프로페셔널함 전달** 의 목적으로 사용합니다.

---

## 0. 컨텍스트 — 무엇이 다른가

일반적인 팀 PR 가이드와 다음이 다릅니다:

- **리뷰어가 본인 1명** → CODEOWNERS, approval, 코멘트 응답 등은 N/A. **셀프 리뷰가 전부**.
- **production 없음** → `dev`/`main` 분리 없이 `main` 단일 브랜치.
- **5일 일정** → 풀 DDD, 100% 커버리지 같은 목표는 비현실적. 현실적 기대치 적용.
- **평가자 시그널 중심** → PR 제목/본문/단위가 평가자가 읽기 좋게 정리되어야 함.

---

## 1. 핵심 원칙

1. **단일 목적**: 한 PR 은 하나의 기능/버그/리팩토링만 담는다.
2. **맥락 제공**: 평가자(=리뷰어)가 변경 의도를 5분 안에 파악할 수 있어야 한다.
3. **빌드 성공 상태로 머지**: 깨진 빌드를 `main` 에 남기지 않는다.
4. **셀프 리뷰 필수**: PR 을 연 직후 본인이 먼저 diff 를 처음부터 끝까지 읽는다.
5. **CLAUDE.md 의 ADR 과 안티패턴을 PR 단위로 검증**.

---

## 2. 브랜치 전략 & 네이밍

### 2.1 기본 브랜치
- **기본 브랜치는 `main`**. 별도 `dev` 분리 없음.
- 새 작업 시작 시 `git checkout main && git pull origin main` 후 분기.
- `main` 직접 push 금지. 모든 변경은 feature 브랜치 → PR → main.

### 2.2 브랜치 네이밍

```
<type>/<scope>-<short-description>
```

예시 (이 프로젝트 기준):
- `feat/server-a-issue-request-api`
- `feat/server-a-rate-limiter`
- `feat/server-b-redis-stock`
- `feat/server-c-redeem-api`
- `feat/outbox-poller`
- `fix/idempotency-cache-ttl`
- `refactor/coupon-issuing-client-interface`
- `chore/docker-compose-kafka-kraft`
- `docs/readme-architecture-diagram`
- `perf/hikari-pool-tuning`
- `test/load-scenarios-k6`

**규칙**: 소문자 + kebab-case, 80자 이내. 모듈명(server-a/b/c)을 scope에 포함하면 추적이 쉬움.

---

## 3. PR 제목

**Conventional Commits 형식**.

```
<type>(<scope>): <subject>
```

이 프로젝트의 scope 컨벤션:
- `server-a`, `server-b`, `server-c` — 서비스 단위 변경
- `common` — 공통 모듈
- `infra` — docker-compose, gradle, CI 등
- `docs` — README, ADR 추가/수정

예시:
- `feat(server-a): Idempotency-Key 헤더 처리 추가`
- `feat(server-b): Redis Lua 기반 재고 차감 구현`
- `feat(server-c): 쿠폰 사용 API 및 낙관적 락 적용`
- `fix(server-a): Circuit Breaker fallback에서 traceId 누락 수정`
- `refactor(server-a): CouponIssuingClient를 인터페이스 + Profile 기반 구현체로 분리`
- `perf(server-a): HikariCP pool size 20 → 30 조정 및 벤치마크`
- `chore(infra): Kafka KRaft mode로 docker-compose 재구성`
- `docs(adr): ADR-002 Saga + Outbox 결정 근거 추가`

**이유**: PR squash merge 시 자동으로 커밋 메시지가 됨. 평가자가 git log 만 봐도 변경 흐름을 따라갈 수 있음.

---

## 4. PR 본문 템플릿

`.github/pull_request_template.md` 로 커밋해서 모든 PR 에 자동 적용.

```markdown
## 🎯 목적 (Why)

<!-- 이 PR 이 왜 필요한가? 어떤 평가 항목 / ADR과 연결되는가? -->

연관: 평가 항목 #X (CLAUDE.md Section 3 참조) / ADR-XXX

## 📋 변경 사항 (What)

<!-- 주요 변경을 개념 수준으로. 파일 나열 X. -->

- 예: server-a 에 Idempotency Filter 추가 (Redis SETNX 기반)
- 예: 동일 Idempotency-Key 재요청 시 캐시된 응답 반환
- 예: server-c 에 `coupon.idempotency_key` UNIQUE constraint 추가 (Flyway V2)

## 🧪 검증

<!-- 어떻게 동작 확인했는지 (수동 curl, 로컬 부하 테스트, 단위 테스트) -->

- [ ] 단위 테스트 추가 (해당하는 경우)
- [ ] curl 시나리오 검증 (아래에 명령어와 응답)
- [ ] 동시성 테스트 (해당하는 경우 — 예: 동일 키 동시 요청 10회)
- [ ] `./gradlew :server-X:bootRun` 정상 기동
- [ ] Redis/MySQL 상태 확인

```bash
# 검증에 사용한 명령어와 결과
KEY=$(uuidgen)
curl -i -X POST http://localhost:8080/api/v1/coupons/issue-requests \
  -H "X-User-Id: 1001" -H "Idempotency-Key: $KEY" -d '{...}'
# → 200 OK
# 같은 KEY 재요청 → 같은 응답, DB record 추가 없음
```

## ⚠️ 트레이드오프 / 결정 메모

<!-- 평가자가 특히 봐야 할 부분, 의도적으로 단순화한 부분 -->

- 예: 처리 중인 요청 race condition 은 "응답 캐싱 후 재현" 방식으로 단순화. lock 기반 처리는 5일 일정상 후속 과제로 README에 명시.
- 예: Rate Limit 임계치 10 req/sec 는 ADR-005에 따른 값. 부하 테스트 후 조정 가능.

## 🔄 Breaking Change

- [x] **없음**
- [ ] **있음**: 영향 범위 / 마이그레이션 방법 기술

## ✅ 체크리스트

### 일반
- [ ] CLAUDE.md 의 ADR 과 안티패턴(Section 10)을 위반하지 않음
- [ ] 패키지 구조 (api / application / domain / infrastructure) 유지
- [ ] `./gradlew clean build` 가 로컬에서 성공
- [ ] 새 의존성 추가 시 CLAUDE.md 기술 스택 표 업데이트

### 코드 컨벤션 (CLAUDE.md Section 11 기반)
- [ ] `@Data` 사용 안 함
- [ ] entity 에 `@Setter` 전체 적용 안 함
- [ ] `LocalDateTime` 대신 `Instant` 사용
- [ ] DTO 는 Java record
- [ ] javax.* 대신 jakarta.* 사용
- [ ] `@Transactional` 안에서 외부 API/Kafka 호출 없음

### 분산 시스템 / 신뢰성 (해당하는 경우만)
- [ ] 외부 노출 API 에 Idempotency-Key 처리가 있는가?
- [ ] 외부 노출 API 에 Rate Limit 정책이 적용되는가?
- [ ] 외부 호출(WebClient/RestClient/Kafka) 에 timeout 이 설정되어 있는가?
- [ ] 외부 호출에 Circuit Breaker 또는 retry 정책이 있는가?
- [ ] Kafka consumer 가 멱등하게 동작하는가? (DB UNIQUE constraint 또는 명시적 dedupe)
- [ ] 재고/한정 자원 관리 시 Redis atomic 연산을 사용했는가? (RDBMS row lock 금지)

### 문서
- [ ] README 의 관련 섹션이 갱신되었는가? (해당하는 경우)
- [ ] 새 ADR 이 필요한 결정이 있다면 docs/decisions/ 에 추가했는가?
- [ ] 셀프 리뷰를 완료했는가?

## 📎 관련 문서

- CLAUDE.md Section X
- ADR-XXX
```

---

## 5. 셀프 리뷰 체크리스트

PR 을 연 직후 **본인이 먼저** 아래를 기계적으로 확인.

### 5.1 코드 품질
- [ ] 추가한 주석이 "왜" 를 설명하는가? ("무엇" 은 코드로)
- [ ] 변수/메서드 이름이 의도를 드러내는가?
- [ ] 매직 넘버 / 매직 스트링이 남아있지 않은가? (특히 Redis 키, timeout 값)
- [ ] 죽은 코드, 주석 처리된 코드가 남아있지 않은가?
- [ ] 예외 처리가 누락된 경로가 없는가?

### 5.2 아키텍처 (CLAUDE.md 기반 — 가벼운 DDD)
- [ ] Controller 가 Application(Service) 만 호출하는가?
- [ ] Application Service 에 비즈니스 규칙이 새지 않았는가?
- [ ] Repository / 외부 client 는 infrastructure 패키지에 있는가?
- [ ] domain 패키지는 Spring/JPA 외부 의존성에 의존하지 않는가? (단, `@Entity` 같은 JPA 어노테이션은 본 프로젝트에서 허용)
- [ ] 서비스 간 직접 DB 공유가 없는가? (Database per Service 원칙)

### 5.3 동시성 / 멱등성 / 신뢰성
- [ ] 동시성이 발생할 수 있는 코드 경로(재고 차감, Idempotency 검사)에 대한 검증 방법이 PR 본문에 있는가?
- [ ] 같은 요청이 N회 들어와도 결과가 같음을 확인했는가?
- [ ] 외부 호출 실패 시 시스템 전체가 무너지지 않는가? (Circuit Breaker, fallback)
- [ ] DB 트랜잭션 안에서 외부 호출(HTTP, Kafka)을 하지 않는가?

### 5.4 테스트 (현실적 기대치)
이 프로젝트는 5일이라 모든 메서드 테스트는 비현실적. **핵심 위험 지점에 집중**:
- [ ] 동시성 핵심 로직 (재고 차감, Idempotency) 에 통합 테스트가 있는가?
- [ ] 멱등성 요구가 있는 endpoint 에 동일 요청 재현 테스트가 있는가?
- [ ] `@Disabled` / `@Ignore` 가 남아있지 않은가?
- [ ] 부하 테스트가 필요한 부분(Day 4) 은 k6 시나리오에 추가되었는가?

### 5.5 성능
- [ ] N+1 쿼리를 만들지 않는가?
- [ ] HikariCP 설정이 1 vCPU 환경에 맞는가? (불필요한 연결 수 증가 방지)
- [ ] Redis 호출이 1 요청당 N번 발생하지 않는가? (pipeline 또는 Lua 활용)

### 5.6 보안 / 로깅
- [ ] 입력 검증(`@Valid`, `@NotNull` 등) 이 controller 에 있는가?
- [ ] 민감 정보(idempotency-key 본문 등) 가 로그에 그대로 남지 않는가?
- [ ] 에러 응답이 내부 stacktrace 를 노출하지 않는가?

---

## 6. PR 크기 기준과 분할

### 6.1 권장 크기
- **S**: 변경 < 100줄 — 5분 안에 셀프 리뷰
- **M**: 변경 100~600줄 — 15분 셀프 리뷰
- **L (분할 고려)**: 600~1200줄
- **XL (반드시 분할)**: > 1200줄

### 6.2 분할 전략

이 프로젝트에서 자연스러운 분할 단위:
1. **Phase 단위 분할** (Day 1 kickoff prompt의 Phase = PR 단위로 자연스럽게 매핑)
   - Phase 1 (Gradle 멀티모듈) = 1 PR
   - Phase 2 (docker-compose) = 1 PR
   - Phase 3 (도메인 모델) = 1 PR
   - Phase 6 (Server A 핵심 API) = 1 PR
   - Phase 7 (Idempotency + Rate Limiter) = 1~2 PR
   - 등등

2. **서비스별 분할**: server-a 변경과 server-b 변경은 분리

3. **리팩토링 선행**: 기능 추가 전 구조 정리는 별도 PR (`refactor:` → `feat:` 순서)

---

## 7. Draft PR

다음 경우 Draft 로 시작:
- 설계가 확정되지 않아 평가자(=본인) 검토가 먼저 필요할 때
- CI/빌드만 먼저 확인하고 싶을 때
- 작업 완료 전 진행 상황을 기록하고 싶을 때

GitHub Draft 기능을 사용. `[WIP]` 접두사는 쓰지 않는다.

---

## 8. 머지 전략

### 8.1 기본: **Squash and Merge**
- 여러 커밋이 `main` 에 하나의 커밋으로 합쳐짐.
- `main` 히스토리가 깔끔.
- PR 제목이 그대로 머지 커밋 메시지가 되므로 **PR 제목 정확히 작성**.

### 8.2 머지 전 최종 확인
- [ ] 빌드(GitHub Actions 또는 로컬 `./gradlew build`) 성공
- [ ] 셀프 리뷰 체크리스트 모두 체크
- [ ] PR 본문의 검증 시나리오가 실제로 동작
- [ ] PR 제목이 머지 후 히스토리에 남기 적절한가?

---

## 9. GitHub 자동화 (선택 — 시간 여유 있을 때만)

5일 일정에 우선순위 낮음. 시간 남으면 추가:

### 9.1 GitHub Actions
- `.github/workflows/build.yml` — `./gradlew build` 실행
- PR title check (Conventional Commits 형식 검증)

### 9.2 Branch Protection (`main`)
- [ ] Require a pull request before merging
- [ ] Require status checks (CI 통과)
- [ ] Require conversation resolution
- (approval 요구는 솔로라 N/A)

이 두 항목만 해도 평가자에게 "기본 위생 갖춘 프로젝트" 시그널.

---

## 10. 안티 패턴

| 안티 패턴 | 올바른 방법 |
|---|---|
| "WIP: 일단 올려봅니다" 로 본 PR 시작 | Draft PR 사용 |
| PR 본문에 "코드 보면 알 수 있습니다" | 목적/변경/검증을 항상 명시 |
| 1,000줄 넘는 단일 PR | Phase / 서비스 단위로 분할 |
| 빌드 깨진 채로 머지 | 로컬 `./gradlew build` 통과 후 머지 |
| 한 PR 에 여러 서비스 변경 혼재 | 가능한 한 서비스별로 분리 |
| ADR 결정을 PR 본문에만 적고 docs/ 에 안 남김 | ADR 은 docs/decisions/ 에 별도 파일 |
| Idempotency / Rate Limit 누락된 채로 외부 API 머지 | 5.3 체크리스트 통과 후 머지 |

---

## 11. 실전 예시

### 11.1 좋은 PR 예시 (요약)

```
제목: feat(server-a): Idempotency-Key 헤더 처리 및 Rate Limiter 추가

목적: 평가 항목 ② (멱등성) 과 ④ (Rate Limit) 구현. CLAUDE.md ADR-004, ADR-005 참조.

변경:
- IdempotencyFilter 추가 (Redis SETNX, TTL 24h)
- RateLimitFilter 추가 (Bucket4j Lettuce backend, user당 10 req/sec)
- GlobalExceptionHandler 에 429, 409 매핑 추가

검증:
- 같은 Idempotency-Key 두 번 호출 → 같은 응답, DB record 1개 (curl 결과 첨부)
- 1초에 15회 호출 → 11번째부터 429 (스크립트 결과 첨부)
- Idempotency-Key 누락 → 400

트레이드오프:
- 처리 중 race condition 은 "응답 캐싱" 방식으로 단순화 (PR 본문 5번 항목)

체크리스트: 모두 통과
```

### 11.2 나쁜 PR 예시
```
제목: update server-a
본문: (비어있음)
변경: 1,200줄, server-a/b 양쪽 변경 + Gradle 설정 + README + idempotency 절반 구현
```

---

## 12. 최종 요약

1. **작은 PR** 을 **자주** 올린다. Phase 단위가 자연스러운 PR 단위.
2. PR 본문은 **평가자에게 보내는 미니 보고서** 다.
3. **셀프 리뷰가 전부**. 본인이 먼저 처음부터 끝까지 diff 를 읽는다.
4. CLAUDE.md 의 ADR 과 안티패턴 위반 여부를 PR 단위로 점검한다.
5. 머지 전 빌드 통과는 절대 양보하지 않는다.

---

**전체 워크플로우**:
```
요구사항 → CLAUDE.md 검토 → 구현 → 셀프 리뷰 → 커밋 → PR → 셀프 검증 → 머지
```