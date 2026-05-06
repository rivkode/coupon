---
name: scope-discipline
description: 시스템 아키텍처 take-home 과제(5일 한정)에서 평가 핵심을 벗어난 과한 구현(over-engineering)을 차단하는 가드레일 스킬. YAGNI 원칙, "정말 필요한가?" 질문 강제, Must vs Nice-to-have 우선순위 점검, 평가 항목 외 영역(보안 hardening / EOS / 풀스택 모니터링 / 멀티 캐시 다단 / Saga Orchestrator 등)을 README 설명으로 대체하는 가이드, 추상화/계층/패턴 남용 안티패턴, 시간 압박 인식, "구현 vs 문서로 설명" 결정 트리를 포함한다. "구현해줘", "추가해줘", "리팩토링", "추상화", "확장성" 같은 표현이 나오거나 `code-planning` 직후 + 작업 중간중간 PROACTIVELY 사용한다. 평가의 핵심은 5축(트래픽/정합성/캐시/유량제어/사이징) 이며 그 외는 README 한 줄로 충분하다는 사실을 메인 에이전트에게 강제한다.
---

# Scope Discipline — 과한 구현 차단 가드레일

> 5일 take-home 시스템 아키텍처 과제는 **모든 것을 만들어내는 게임이 아니라, 평가 핵심을 명확히 보여주는 게임**이다.
>
> 평가자가 5분 안에 5축(트래픽/정합성/캐시/유량제어/사이징)을 어떻게 다뤘는지 파악할 수 있게 하는 것이 1차 목표. 운영 환경급의 모든 비기능을 코드로 증명할 필요는 없다.

이 스킬은 **무언가를 추가하기 직전마다** 호출되어야 한다. 한 번 호출하고 끝나는 스킬이 아니다.

---

## 1. 추가 결정 트리 — 모든 새 추상/계층/기능에 적용

```
이 코드/기능/모듈을 추가하려는데...
├── PRD 가 명시한 요구사항인가?
│   ├── 예 → Must. 진행.
│   └── 아니오 → 다음 질문
│
├── PRD 평가 항목 5축 중 하나의 직접 증명에 필요한가?
│   ├── 예 → Must. 진행.
│   └── 아니오 → 다음 질문
│
├── 없으면 평가자가 "왜 안 했지?" 라고 묻겠는가?
│   ├── 예 → Nice. **README 한 줄 설명 + 미구현 명시** 로 갈음 가능 여부 검토
│   └── 아니오 → **만들지 않는다**. 시간 절약 → Must 강화로 회귀
│
└── 만들기로 결정한 것조차도:
    └── "가장 단순한 동작 버전" 부터. 추상화/일반화/확장 포인트는 두 번째 동일 사례가 보인 후.
```

---

## 2. 시스템 아키텍처 과제의 "Must / Nice / Skip" 매트릭스

PRD 평가 항목과 매핑:

| 영역 | Must (코드로 증명) | Nice (시간 남으면 코드) | Skip (README 한 줄) |
|---|---|---|---|
| **레이어드 + 가벼운 DDD** (CLAUDE.md §11) | Domain ↔ JpaEntity 분리 + `XxxJpaEntity` 네이밍 + Mapper. Aggregate 개념까지 | 도메인 이벤트, ADR | 풀 DDD (Bounded Context, Strategic DDD, Modulith) |
| **A→B→C 데이터 흐름** (CLAUDE.md §4) | 필수. A→B 동기 + B→C 비동기 | 모든 경계에 Outbox + CDC | Saga Orchestrator 별도 모듈 |
| **Idempotency** (ADR-004) | 진입(A) Redis 캐시 + C UNIQUE | B 자체 멱등성 강화 | 키 라이프사이클 GC 잡 |
| **재고 차감** (ADR-003) | Redis Lua + sharding | 코드 풀 사전 생성 | RDBMS 락 fallback (안티) |
| **Rate Limit** (ADR-005) | Bucket4j 사용자당 10 req/sec | Redis backend + Bulkhead | 다층 rate limit (사용자/IP/엔드포인트) |
| **A→B 호출 보호** (ADR-001) | Resilience4j CB + timeout 200ms | Bulkhead + 메트릭 | adaptive timeout |
| **캐시** | 재고 sharding + Idempotency 캐시 | Stampede 방지 1가지 | L1+L2 다단, 적응형 |
| **부하 테스트** (CLAUDE.md §12 Day 4) | k6 smoke + load + spike | stress, soak | CI 파이프라인 자동화 |
| **사이징 산식** | k6 측정 + Little's Law | p95/p99 두 산식 비교 | USL 회귀 측정 |
| **에러 응답 / traceId** | 표준 ErrorResponse + MDC traceId | structured JSON 로깅 | Prometheus + Grafana |
| **README** | CLAUDE.md §3 5축 + 다이어그램 + 결과 | Mermaid 다이어그램 추가 | API 명세 자동 생성 |
| **테스트** | Domain 단위 + 동시성 통합 1개 | Application + Infrastructure 슬라이스 | 100% 커버리지 |
| **보안** | 단순 user_id 헤더 + 입력 검증 | 시크릿 분리 + JWT | OWASP 풀 매핑 |
| **분산 트랜잭션** | 없음 (의미적 exactly-once) | Outbox + Consumer 멱등성 | EOS / JTA / XA |

**규칙**: Skip 영역은 **README §7 (트레이드오프)에 한 줄 설명** 으로 충분. 평가자에게 "알고는 있지만 시간/제약상 안 함" 시그널이 핵심.

---

## 3. "Skip 인데 README 로 대체" 의 표준 문장 예시

평가자가 "왜 안 했지?" 를 발견하기 전에 **먼저 명시**한다.

```markdown
## 7. 트레이드오프 / 미구현

### 분산 rate limit
단일 인스턴스 가정으로 Bucket4j in-memory 만 적용했다. 멀티 인스턴스 운영
전환 시 Bucket4j Redis ProxyManager 또는 Redis-Cell 로 교체. 본 과제는
사이징 산식(§10)에서 N 인스턴스 수만 계산하고 분산 rate limit 코드는
생략했다.

### Saga Orchestrator
A→B→C 흐름은 Choreography(이벤트 기반) 로 처리하며 별도 Orchestrator 는
도입하지 않았다. 단계가 4개 이하라 Choreography 가 명확하다고 판단했다.
운영 환경에서 분기/재처리 흐름이 늘어나면 Orchestrator 도입 검토.

### EOS / 분산 트랜잭션
Kafka EOS 또는 JTA 는 사용하지 않았다. Outbox + Consumer 멱등성으로
"의미적 exactly-once" 를 구현했다 (at-least-once 전송 + 중복 흡수).
이 과제 트래픽 / 인프라 제약(1 vCPU/2GB) 에 EOS 비용은 과하다고 판단.

### Hot Key 자동 감지
정적 key sharding 만 적용했다. 운영 환경에서 동적 hot key 감지 +
적응형 캐싱 (Sentinel hot-key 등) 은 운영 메트릭이 쌓인 후 도입.

### 보안 hardening
인증/인가 + 입력 검증 + 시크릿 분리만 구현. WAF, RASP, OWASP 풀 매핑,
감사 로그는 본 과제 평가 핵심에서 제외했다.

### 풀스택 Observability
구조화 로깅 + traceId + 표준 ErrorResponse 까지. Prometheus / Grafana /
Loki 풀스택은 본 과제에서 제외하고 Actuator 기본 endpoint 만 노출.
```

이 7~10줄로 "안 한 것" 을 다 처리한다. **코드로 증명할 필요 없음**.

---

## 4. 자주 발생하는 과한 구현 — 즉시 차단

### AP1. "혹시 모르니" 추상 인터페이스

```
❌ 문제: Repository 인터페이스 + Memory 구현 + JPA 구현 + Mongo 구현 (다 만듦)
✅ 수정: 실제 사용하는 구현 하나만. 두 번째가 필요해질 때 인터페이스 분리.
```

### AP2. 모든 외부 호출에 일반화 어댑터

```
❌ 문제: HttpClient 추상화 → AbstractRetryableHttpClient → 모든 호출 통합
✅ 수정: 호출이 1군데면 그냥 RestClient 직접 사용. 3군데 이상 + 공통 정책 명확할 때 추상화.
```

### AP3. "나중을 위한" 이벤트 시스템

```
❌ 문제: 도메인 이벤트가 1개뿐인데 EventBus + AsyncEventDispatcher + DLQ 까지 풀스펙
✅ 수정: ApplicationEventPublisher 한 줄. 이벤트 2~3개 늘어나면 그때 강화.
```

### AP4. Configuration 풀세트

```
❌ 문제: dev/staging/prod/test 4개 profile + 각 profile 마다 application-*.yml
✅ 수정: 과제는 local 1개로 충분. README 에 "운영 전환 시 profile 분리" 한 줄.
```

### AP5. 모든 메서드에 메트릭 + 로그

```
❌ 문제: @Timed, @Counted, log.info() 가 모든 public 메서드에
✅ 수정: 진입점(Controller) 한 곳에 표준 traceId 로깅. 핵심 비즈니스 1~2개에만 메트릭.
```

### AP6. Test Container 풀세트

```
❌ 문제: MySQL + Redis + Kafka + ZooKeeper + MongoDB 다 띄워서 통합 테스트
✅ 수정: Domain 단위 테스트 + 핵심 통합 1개 (가장 위험한 흐름). 나머지는 README 설명.
```

### AP7. 캐시 다단 풀스펙

```
❌ 문제: L1 Caffeine + L2 Redis + L3 CDN + Pub/Sub 무효화 + PEE + sharding 풀세트
✅ 수정: PRD 가 Hot Key 명시 → L1 또는 L2 한 가지 + sharding/PEE 중 하나만. 나머지 README.
```

### AP8. ArchUnit / 코드 메트릭 자동화

```
❌ 문제: ArchUnit 풀룰 + JaCoCo + SpotBugs + Checkstyle + PMD 풀세팅
✅ 수정: 과제 평가에서 거의 안 본다. README 에 "ArchUnit 도입 검토 가능" 한 줄.
```

### AP9. 다이어그램 풀스펙

```
❌ 문제: PlantUML / draw.io / Mermaid 시퀀스/클래스/배포/컨텍스트 5개씩
✅ 수정: Mermaid 데이터 흐름 1개 + 도메인 상태 전이 1개 = 2개로 충분.
```

### AP10. README 가 너무 김

```
❌ 문제: README 5,000+ 줄. 평가자가 5분에 못 읽음.
✅ 수정: 1~10번 섹션 각각 짧게. 표 / 다이어그램으로 압축. 자세한 건 ADR 로 분리.
```

---

## 5. "구현 vs 문서로 설명" 결정 표

| 상황 | 권장 |
|---|---|
| PRD 가 직접 측정/증명을 명시함 | **코드 + 결과 캡처** |
| PRD 평가 항목의 핵심을 한 코드 경로로 보여줄 수 있음 | **코드** |
| 평가 항목이지만 운영 전환 시점 결정에 가까움 | **README + ADR** (사이징 한계 등) |
| 평가 항목 외 영역인데 "프로페셔널해 보이려고" | **README 한 줄** 또는 **생략** |
| 시간이 부족하지만 어느 정도 보여줘야 함 | **README + 1줄 코드 스텁 + TODO 명시** 보다는 **README 만** |

---

## 6. 작업 진행 중 5분마다 자가 점검

다음을 마음 속으로 묻는다:

1. 지금 만들고 있는 것이 **PRD 평가 5축 중 어느 축의 증명** 인가?
2. 이걸 빼고 README 한 줄로 대체하면 평가자가 정말 감점하는가?
3. 더 단순한 버전이 있는가? (한 단계 추상화 빼기)
4. 같은 시간으로 **Must 항목의 깊이를 더 늘릴 수** 있는가?
5. 마지막 30분에 README 다듬을 시간이 있는가?

5분이 넘는 시간을 한 항목에 쏟고 있다면 **즉시 멈추고** 위 5개 질문에 답한다.

---

## 7. 시간 분배 (CLAUDE.md §12 기준)

CLAUDE.md §12 의 5일 로드맵을 그대로 따른다.

| 일자 | 주력 | 부력 |
|---|---|---|
| Day 1 | 셋업 + 설계 문서 (repo, docker-compose, CLAUDE.md/README 초안, 도메인 모델) | README §1~§3 |
| Day 2 | Server A + B 핵심 (Rate Limiter, Idempotency, Redis Lua 재고 차감, A↔B sync) | README §4~§5 |
| Day 3 | Server C + Outbox/Saga (Kafka producer/consumer, Redeem API, 멱등성) | README §6 |
| Day 4 | 부하 테스트 (k6) + 튜닝 (HikariCP/JVM) + 결과 보고서 초안 | README §9 §10 |
| Day 5 | **README 최종 + 사이징 (100k 인스턴스 수 계산) + 영상/스크린샷 + 셀프 리뷰** | 절대 코드 추가 X |

**Day 5 는 코드 추가 금지**. 발견된 명백한 버그만 수정. 나머지는 README §7 트레이드오프로 흡수.

---

## 8. 이 스킬이 막아야 할 신호

메인 에이전트가 다음 중 하나라도 시작하면 **즉시 차단**하고 본 스킬 §1 결정 트리로 돌려보낸다:

- "확장성을 위해 인터페이스를 추가하자"
- "나중에 다른 구현이 필요할 수 있으니"
- "OWASP 전체를 검토하자"
- "Saga Orchestrator 별도 모듈로 빼자"
- "L1 + L2 + L3 캐시 다단 + 동기화"
- "ArchUnit 으로 의존성 자동 검증 추가"
- "Prometheus + Grafana 대시보드 띄우자"
- "모든 endpoint 에 @Timed 추가"
- "Saga 보상 트랜잭션 자동화 모듈"
- "Kafka EOS / JTA"
- "테스트 커버리지 100% 달성"
- "JaCoCo + SonarQube 연동"
- "Multi-region failover 시뮬레이션"

각 항목에 대한 표준 응답: **"§3 README 트레이드오프에 한 줄로 명시하고 다음 Must 로 회귀."**

---

## 9. 자가 검증 체크리스트 (작업 종료 시)

- [ ] 모든 새 추상/계층/패턴이 §1 결정 트리를 통과했는가?
- [ ] §2 매트릭스의 **Must 영역이 95%+ 완성** 인가?
- [ ] **Skip 영역** 은 코드 없이 README §7 에 명시되어 있는가?
- [ ] README 가 **5분 이내 핵심 5축 파악 가능** 한가?
- [ ] 마지막 하루(Day 5) 에 **코드 추가 없이 README + 클론 검증** 만 했는가?
- [ ] "TODO" / "FIXME" / "wip" / 죽은 코드가 남아있지 않은가?
- [ ] 빌드 / 테스트 / k6 smoke 가 클론 직후 **한 명령** 으로 통과하는가?

---

## 10. 다음 단계

본 스킬은 다른 모든 스킬의 **상위 가드레일** 이다. 어느 스킬에서든 새 코드/추상을 만들기 전에 본 스킬 §1 결정 트리를 통과해야 한다.

회귀 흐름:
- "이거 추가해야 할까?" 의문 → 본 스킬 §1
- 의구심 → CLAUDE.md §3 (5축) / §10 (안티패턴) 회귀
- 결정 후 코드 → 해당 5축 스킬 (`concurrency` / `system-design` / `cache-strategy` / `rate-limiting-backpressure` / `capacity-planning` / `k6-load-testing`)
- 결과 검증 → `code-reviewer` agent (CLAUDE.md §10 + `scope-discipline` 위반 함께 점검)
