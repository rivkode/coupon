# Promotion 과제 — 요구사항 분석

> 원본: `docs/prd/promotion-prd.md`
> 작성일: 2026-05-06
> 분석 단계: PRD 정독 → 모호점/가정 명문화 → 평가 5축 매핑 → 우선순위 결정 → 에이전트/스킬 충분성 점검

본 문서는 **구현 착수 직전의 합의문**이다. 여기서 명문화한 가정 / 우선순위는 이후 모든 설계·구현·문서 단계의 기준선이 된다.

---

## 1. PRD 재진술 (내가 이해한 것)

### 1.1 만들어야 할 것

```
user → front → api call → ServerA (RDBMS) → ServerB (NoSQL) → ServerC (RDBMS)
```

- **3 개의 독립 기동/배포 가능한 백엔드 서버**를 **하나의 git repo (멀티 모듈)** 안에 구성한다.
- front (브라우저/클라이언트) 코드는 포함하지 않는다. 도메인이 다르다는 것을 표시하는 용도일 뿐.
- 각 노드의 데이터 저장소가 **명시적으로 다르다** (RDBMS / NoSQL / RDBMS).

### 1.2 트래픽 규모

| 항목 | 값 |
|---|---|
| 총 사용자 | 1,000 명 |
| 사용자당 부하 | 10 초 내 100 건 |
| 전체 평균 TPS | 약 10,000 TPS |
| **노드 1대당 목표 TPS** | **500 ~ 1,000 TPS** |
| 데이터 형식 | JSON 10 필드 |
| 프로토콜 | HTTPS |

→ 단일 노드 1,000 TPS 면 평균 10 ~ 20 대로 분산 처리하는 시나리오.

### 1.3 인프라 제약

| 항목 | 값 |
|---|---|
| 서버 사양 (각 노드) | **1 vCPU / 2 GB RAM** |
| 권장 스택 | Java / Spring Boot |
| Server A 저장소 | RDBMS — 사용자 요청 기록 + 유효성 검증 |
| Server B 저장소 | NoSQL — 중간 데이터 가공 + 캐싱 |
| Server C 저장소 | RDBMS — 최종 데이터 영구 저장 |

### 1.4 평가 5축 (원문 그대로)

| 축 | 핵심 질문 |
|---|---|
| ① **대량 트래픽 + 동시성** | 데이터 유실 없이 RDBMS 기록 / Connection Pool / DB Lock 최소화 |
| ② **분산 정합성** | A→B→C 흐름에서 장애 시 유실 방지 / 동일 요청 N회 시 C 의 중복 방지 (Idempotency) |
| ③ **캐시 + Hot Spot** | 읽기/쓰기 집중 구간 캐시 / 특정 Key 폭주(Cache Scorch) 방지 |
| ④ **Rate Limit + Backpressure** | Server A 처리 능력 초과 시 카스케이딩 실패 차단 |
| ⑤ **인프라 사이징** | 1 vCPU/2 GB 단일 노드 TPS 벤치마크 → **100k 동접 시 필요 인스턴스 수 산출** |

### 1.5 제출물

- GitHub private repo + collaborator 초대
- README: 아키텍처 다이어그램(Data Flow), 동시성/정합성 전략, 부하 테스트 결과, 인프라 확장 계산

### 1.6 명시된 자유도

> "하기 평가 항목의 모든 내용을 구현해 주셔도 됩니다만, **시간이 부족할 경우 설명으로 대체** 할 수 있습니다."
>
> "**좀 더 자신 있는 영역에 포커스** 해서 구현체를 제출하셔도 됩니다."

→ 평가자는 코드와 README 설명을 **함께** 본다. 5축 모두 코드로 증명할 필요 없음. 하지만 코드 없이 설명만 하는 영역은 **명시적으로 트레이드오프 섹션에 적어야** 감점되지 않는다.

---

## 2. PRD 의 모호점과 가정 (Assumptions)

PRD 가 명시하지 않아 **임의로 해석한** 부분. README §6 (가정) 에 그대로 옮겨 적는다.

### A1. 도메인 의미

PRD 는 "promotion (프로모션)" 이라는 카테고리만 명시할 뿐 비즈니스 행위는 모호하다.
**가정**: "사용자가 프로모션 (예: 쿠폰/포인트 지급) 을 요청하면 서버 측 제한된 리소스가 사용자에게 지급되는 흐름" 으로 해석. PRD 본문도 "사용자 요청 건 수에 제한이 있다 = 서버측 제한된 리소스를 사용자 요청에 의해 사용자에게 지급" 이라고 표현.

→ **선착순 쿠폰 지급** 모델로 도메인 구체화.

### A2. 사용자당 100 건 = 100 종류 vs 100 시도?

PRD: "사용자는 10 초 이내에 100 건의 요청"
**가정**: 같은 사용자가 100 번의 **개별 요청** 을 보낸다. 한 사용자에게 지급 가능한 프로모션 수에는 별도 제한 (예: 1 일 1 회 / 1 회만 등) 이 있을 수 있으므로, **API 자체는 100 건을 받지만 비즈니스 규칙으로 일부는 거부** 될 수 있다.

→ 즉 트래픽 부하 ≠ 비즈니스 성공 건수.

### A3. Idempotency Key 발급 주체

PRD: 명시 안 됨.
**가정**: 클라이언트가 UUIDv4 로 발급해 헤더 `Idempotency-Key` 로 전달. 서버는 검증만.

### A4. 동기 vs 비동기 응답

PRD: 명시 안 됨.
**가정**: A 는 사용자에게 **즉시 ACCEPTED (202)** 응답. C 까지의 영구 저장은 비동기로 진행. 사용자가 결과를 확인하려면 별도 status 조회 API.

→ 1 vCPU 환경에서 동기 체인은 카스케이딩 실패 위험 (`scope-discipline`/`system-design` 권장).

### A5. NoSQL 종류

PRD: "NoSQL" 만 명시.
**가정**: **MongoDB** 를 채택. 사유:
- 스키마 유연 (가공 데이터 형태 변경 흡수)
- TTL 인덱스로 자동 만료 (B 는 "캐싱" 역할도 명시됨)
- Document 단위 upsert 가 자연스러운 멱등성 처리에 유리
- Spring Data MongoDB 로 학습/운영 비용 낮음

> Redis 는 "캐시 + Rate Limit" 으로 별도 사용. B 의 영속 가공 결과는 MongoDB.

### A6. 노드 간 통신 프로토콜

PRD: 명시 안 됨.
**가정**: **Outbox 패턴 + Apache Kafka** 비동기 메시징.

**근거**:
- 본 과제의 본질은 "1 vCPU/2 GB × 3 대로 10,000 TPS 를 안정적으로 처리" — 인프라 제약을 **소프트웨어(큐 + 비동기 + 백프레셔)** 로 푸는 게 평가 핵심.
- 동기 HTTP 체인 (A→B→C) 은 가장 느린 노드가 전체 SLA 를 결정 → 1 vCPU 환경에서 카스케이딩 실패 위험. 평가 항목 ② (분산 정합성) + ④ (Backpressure) 의 정답에 가깝지 않음.
- HTTP + Outbox Polling Relay 만 쓰면 Relay 가 결국 RestClient 로 동기 호출 → 사실상 동기 색이 남음.
- Kafka topic 이 노드 간 자연스러운 백프레셔 지점 (consumer concurrency 로 흡수, 큐 깊이로 spike 흡수).
- 5 일 과제에 Kafka broker 1 대 docker-compose + Spring Kafka producer/consumer 정도는 학습/운영 비용 합리적.

**제외**:
- Kafka EOS (transactional producer / consumer-transactional-id) — Skip. **at-least-once 전송 + Consumer 멱등성 = 의미적 exactly-once** 로 처리.
- Saga Orchestrator 별도 모듈 — Skip. **Choreography (이벤트 기반)** 로 4 단계 이하 흐름 처리.
- Schema Registry / Avro — Skip. JSON + `shared-contracts` record 로 충분.

운영 전환 시점: 트래픽 증가로 partition rebalancing / EOS 가 필요해지면 ADR 로 별도 결정.

### A7. 인증/인가

PRD: 명시 안 됨.
**가정**: 단순 Bearer 토큰 (정적 토큰 또는 사용자 ID 헤더) 만 적용. 본격 인증 서버 / OAuth 는 평가 5축 외 영역 → README §7 트레이드오프.

### A8. 100k 동접의 의미

PRD §⑤: "실제 100,000 명이 동시 접속하는 서비스"
**가정**:
- "동시 접속" 을 "동시 활성 사용자 (active concurrent users)" 로 해석.
- 사용자당 평균 1 RPS 가정 (테스트 시나리오의 평균 1 user/sec; 부하 모델 보다는 보수적).
- 따라서 **목표 평균 도착률 λ = 100,000 req/sec** (피크 1.5 배 = 150,000).
- 다른 해석 (PRD 의 "10 초 안 100 건" 을 100k 사용자에 그대로 적용 = 1,000,000 req/sec) 도 README 에 함께 적되 권장 산식은 1 RPS 가정 기준.

### A9. 배포 토폴로지

PRD: 명시 안 됨.
**가정**: 본 과제는 **각 노드 1 인스턴스 + Kafka 1 broker + Redis 1 + MySQL × 2 + MongoDB 1** 로 docker-compose 로컬 검증.

다만 **분산 멀티 인스턴스 운영을 가정한 코드** 로 작성 (단일 인스턴스 한정 코드 회피):
- **분산 rate limit** — Bucket4j + Redis ProxyManager. 인스턴스 N 대로 늘려도 같은 bucket 참조.
- **분산 락 (Redisson)** — Cache stampede 방지 single-flight 에만 사용. 비즈니스 락은 미사용 (멱등성 키 + DB UNIQUE 가 처리).
- **L1 (Caffeine) + L2 (Redis) 다단 캐시** — 인스턴스 로컬 L1 + 공유 L2. 무효화는 Redis Pub/Sub 으로 다른 인스턴스 L1 비움.

운영 시 인스턴스 수 산출은 README §10 (Little's Law).

### A10. 데이터 형식

PRD: "10 개의 필드를 가진 JSON 객체"
**가정**: 사용자 ID, 프로모션 ID, 요청 시각, 요청 수량, 사용자 컨텍스트 (region, deviceType 등) 등 **10 필드를 가진 PromotionRequest** 로 구체화. 정확한 필드 목록은 implementation-prd 에서 확정.

---

## 3. 평가 5축 → 본 프로젝트 매핑

| 축 | 우리 프로젝트의 답 (요약) | 코드/문서 비중 |
|---|---|---|
| ① 동시성 | A: 사용자당 멱등성 키 + idempotent_records 테이블 / C: 원자적 `INSERT ... ON DUPLICATE KEY UPDATE` / 외부 호출은 트랜잭션 밖 / HikariCP poolSize 산식 | **코드** |
| ② 정합성 | A: Outbox 패턴으로 RDBMS commit + Kafka publish 원자성 / 같은 eventId 가 A→B→C 까지 추적 / B/C consumer 멱등성 / at-least-once + Consumer 멱등 = 의미적 exactly-once | **코드** |
| ③ 캐시/Hot Spot | **L1 (Caffeine 인스턴스 로컬, 10 s) + L2 (Redis, 5 m + jitter) 다단** / **Hot Key sharding (16 shard)** / **Stampede 방지 PEE + single-flight (Redisson)** / 무효화는 Redis Pub/Sub 으로 다른 인스턴스 L1 동기화 | **코드** |
| ④ Rate Limit/Backpressure | **A 진입에 Bucket4j + Redis (분산 rate limit)** 사용자별 100 req/10 s → 429 + Retry-After / **Kafka topic 이 노드 간 자연 백프레셔** (consumer concurrency 흡수) / B/C consumer 측에 Resilience4j Bulkhead + Circuit Breaker | **코드** |
| ⑤ 사이징 | k6 smoke + load 측정 → 1 vCPU 단일 노드 TPS / Little's Law 적용 → 100k 동접 시 인스턴스 수 / DB / Redis / Mongo / Kafka 별도 산정 | **코드 (벤치마크) + README** |

→ 5축 모두 코드로 증명. 다만 Kafka EOS / Saga Orchestrator 별도 모듈 / Hot Key 자동감지 / USL 회귀 측정 등은 **README §7 트레이드오프** 로 흡수.

---

## 4. Must / Nice / Skip 매트릭스

`assignment-prep` §3 + `scope-discipline` §2 매트릭스를 본 PRD 에 맞춰 구체화.

> 본 매트릭스는 사용자가 **5 축 (트래픽/정합성/캐시/유량제어/사이징) 의 핵심 시그널** 을 코드로 보여주기 위해 `scope-discipline` §2 의 기본 매트릭스보다 일부 항목을 **Must 로 승격** 했다. 5 일 안에 가능한가는 §6 (시간 분배) 에서 검증.

### 4.1 Must (반드시 코드로)

**진입 / 정합성**
- A 진입: REST API + `Idempotency-Key` 헤더 + idempotent_records 테이블 lookup + RDBMS 1 차 기록 + Outbox insert (한 트랜잭션)
- **Apache Kafka** topic 으로 A→B / B→C / C→A 비동기 통신
- **Outbox + Kafka Producer Relay** (RDBMS commit 직후 별도 워커가 미발행 row → Kafka publish → published_at 갱신)
- B consumer: 같은 eventId 멱등성 (MongoDB upsert by `_id = eventId`)
- C consumer: `INSERT ... ON DUPLICATE KEY UPDATE` + UNIQUE (event_id)
- 보상 흐름 (B/C 실패 → A status 갱신, Choreography)

**동시성**
- HikariCP poolSize 산식 + 측정 후 조정
- 외부 호출은 트랜잭션 밖 (락 보유 시간 폭주 회피)

**Rate Limit / Backpressure**
- **분산 rate limit**: Bucket4j + Redis ProxyManager (사용자별 100 req/10 s)
- 진입 응답 표준: 429 + Retry-After + 표준 ErrorResponse
- B/C consumer 측에 Resilience4j **Bulkhead + Circuit Breaker** (외부 호출 격리)
- Tomcat `max-threads` / `accept-count` 1 vCPU 기준 산정

**캐시 + Hot Spot**
- **L1 (Caffeine) + L2 (Redis) 다단 캐시** + TTL jitter
- **Hot Key key sharding (16 shard)** — 인기 promotion 카탈로그
- **Cache Stampede 방지**: Redisson 분산 락 single-flight + PEE (Probabilistic Early Expiration)
- 무효화: 쓰기 시 L1+L2 evict + Redis Pub/Sub 으로 다른 인스턴스 L1 동기화

**구조**
- DDD 4-layer (각 노드)
- 표준 ErrorResponse + traceId (MDC + `X-Request-Id`)
- shared-contracts 모듈 (이벤트 record 공유)

**검증**
- k6 smoke + load 시나리오 + thresholds (`p95<300, error<1%`)
- 1 vCPU/2 GB Docker 환경 단일 노드 TPS 측정 + 캡처
- Little's Law 산식 + DB / Redis / Mongo / Kafka 개별 사이징 (README §10)

### 4.2 Nice (시간 남으면 코드, 아니면 README)

- ADR 3 개 (Kafka + Outbox 채택 / MongoDB 선택 / DDD 4-layer)
- Mermaid 데이터 흐름 + 상태 전이 다이어그램
- 통합 테스트 1 건 (TestContainers 로 Kafka + Redis + MySQL + MongoDB 전부)
- Negative caching (존재하지 않는 키)
- k6 stress 시나리오 (한계 탐색)
- Micrometer 핵심 비즈니스 메트릭 (단, `http.server.requests` 자동 측정 외 추가는 선택)

### 4.3 Skip (README §7 한 줄로 충분)

- **Kafka EOS / Transactional Producer** — at-least-once + Consumer 멱등으로 의미적 exactly-once 충분
- **Saga Orchestrator 별도 모듈** — Choreography (이벤트 기반) 로 4 단계 처리
- **Hot Key 자동 감지 / 적응형 캐싱** — 정적 16 shard 만 적용
- **USL 회귀 측정** — 선형 가정 + DB/Redis 한계 명시로 갈음
- **풀스택 모니터링** (Prometheus + Grafana + Loki) — Actuator 기본 endpoint 만
- **ArchUnit / SonarQube / SpotBugs**
- **WAF / RASP / OWASP 풀 매핑**
- **Multi-region failover / Schema Registry / Avro**
- **분산 트랜잭션 (JTA / XA)**
- **인증 서버 별도 구현** — Bearer 토큰 stub 만

---

## 5. 에이전트 / 스킬 충분성 점검

### 5.1 Agent 3 종 — **충분**

| Agent | 역할 | 본 PRD 에 적합한가 |
|---|---|---|
| `code-reviewer` | 코드 리뷰 (DDD 계층, 에러 처리, 안티패턴) | ✅ |
| `ddd-architect` | 도메인 모델 검증 (Aggregate / VO / 불변식) | ✅ |
| `system-design-reviewer` | 분산 디자인 5축 검증 | ✅ — PRD 5축과 정확히 매핑 |

> ⚠️ 주의: `code-reviewer` 의 §2 / §2.1 에 **gRPC / contracts 모듈 / common-infrastructure** 같은 멀티서비스 MSA 체크리스트가 들어 있다. 본 PRD 는 그 정도 복잡도가 아니므로 **단일 모듈 또는 간단한 멀티모듈 (3 개 서버 모듈 + 공통 모듈)** 수준에서만 적용. 메인 에이전트가 호출 시 "본 프로젝트는 단일 모듈 / 단순 멀티모듈" 을 명시해 주면 OK.

### 5.2 Skill 15 종 — **충분 + 일부 과한 부분 있음**

| Skill | PRD 매핑 | 비고 |
|---|---|---|
| `assignment-prep` | 진입 — 가정/우선순위/README 골격 | ✅ 본 분석 문서가 본 스킬 §1~§3 산출물 |
| `code-planning` | 모든 코드 작업 진입점 | ✅ |
| `scope-discipline` | 모든 단계 가드레일 | ✅ — 5일 과제 over-engineering 차단 |
| `system-design` | 평가 ②, A→B→C, Outbox, Idempotency | ✅ — 본 PRD 의 흐름 그대로 예시 사용 |
| `ddd-architecture` | 각 노드 내부 구현 | ✅ |
| `concurrency` | 평가 ①, A 의 idempotent insert / C 의 ON DUPLICATE | ✅ |
| `rate-limiting-backpressure` | 평가 ④, A 진입 Bucket4j / B 호출 Bulkhead | ✅ |
| `cache-strategy` | 평가 ③, Hot Spot / Cache Stampede | ✅ — 단 §4.4 (Hot Key 자동 감지) 는 Skip 영역 |
| `capacity-planning` | 평가 ⑤, Little's Law / 인스턴스 수 | ✅ |
| `k6-load-testing` | 평가 ⑤ 측정 | ✅ |
| `testing-junit` | 계층별 테스트 | ✅ |
| `observability` | 표준 ErrorResponse + traceId | ✅ |
| `documentation` | README + ADR + Swagger | ✅ |

### 5.3 부족한 부분 — **명시적으로 추가가 필요한 스킬은 없음**

다음 사항은 **새 스킬을 만들지 않고** 기존 자산으로 충분히 처리 가능하다:

| 누락처럼 보이는 것 | 실제 처리 방법 |
|---|---|
| Gradle 멀티 프로젝트 구성 가이드 | implementation-prd 에 `settings.gradle.kts` + 모듈 트리 1 개 명시 (1 회성 결정) |
| 노드 간 통신 프로토콜 결정 | `system-design` §3 결정 트리 + ADR-0002 (HTTP+Outbox 채택) |
| Server A/B/C 별 데이터 모델 (스키마 / 컬렉션) | implementation-prd 의 §6 ~ §8 에서 1 회성 정의 |
| Docker Compose 구성 | `assignment-prep` §5 가 표준 골격 제공 |
| 보안 (인증) | 평가 5축 외. README §7 트레이드오프 처리 |
| CLAUDE.md | 현재 부재. .claude/skills 가 같은 역할 수행하므로 추가 불필요 — 다만 메인 에이전트 진입 시 명시적 신호로 `assignment-prep` → `code-planning` 순서를 강제하려면 1 페이지 CLAUDE.md 가 있으면 좋음 (Optional) |

→ **결론: 새 스킬 추가 불필요. 단, 본 분석에서 정한 가정과 매핑이 일관되게 적용되도록 implementation-prd 가 필요.**

### 5.4 Over-engineering 위험 신호 — **현재 자산 안에 잠재**

본 과제는 평가 5 축 (트래픽/정합성/캐시/유량제어/사이징) 을 **코드로 시그널링** 하는 게 핵심이므로 `scope-discipline` 의 기본 Skip 항목 중 일부는 Must 로 승격됐다 (§4.1). 그러나 다음은 여전히 위험으로 명시적 차단:

| 위험 | 차단 방법 |
|---|---|
| `code-reviewer` §2.1 의 풀 MSA 체크 (gRPC contracts deadline 등) | 본 과제는 **Spring Kafka + JSON** 채택. gRPC / contracts proto 는 NA 처리. 단, Kafka producer/consumer 멱등성 + Outbox 체크는 적용 |
| `cache-strategy` §4.4 Hot Key 자동 감지 / 적응형 | Skip — 정적 16 shard 만. 자동감지는 README 트레이드오프 |
| `capacity-planning` §3.3 USL 회귀 측정 | Skip — README 에 "선형 가정" + DB 한계 명시로 갈음 |
| `system-design` §6.2 Saga Orchestrator 별도 모듈 | Skip — Choreography (각 노드 자체 보상 이벤트 발행) 고정 |
| Kafka EOS / Transactional Producer / Schema Registry | Skip — at-least-once + Consumer 멱등 = 의미적 exactly-once |
| `concurrency` §2.4 Redis 카운터 + 비동기 DB 동기화 | Skip — 본 과제는 선착순 쿠폰 카운터 차감이 핵심이 아님. 멱등성 키 + UNIQUE 로 충분 |
| `ddd-architecture` 의 헥사고날 매핑 (Port/Adapter) | DDD 4-layer 단일 채택. 헥사고날 용어 금지 (이미 §8 에 명시) |
| 풀스택 Observability (Prometheus + Grafana + Loki) | Actuator `health, info` + Micrometer `http.server.requests` 자동 측정만 |

---

## 6. 다음 단계

본 분석 문서를 기준선으로 다음 산출물을 만든다.

1. **`docs/prd/implementation-prd.md`** — 구현 PRD (모듈 구조, API, 데이터 모델, TodoList)
2. (선택) **`docs/adr/0001-architecture-decisions.md`** — 한 파일에 ADR 3 개 묶음 (Outbox / MongoDB / DDD 4-layer)
3. **메인 에이전트 진입 시**: `assignment-prep` → `code-planning` (이미 본 문서가 §1~§3 을 채움) → `system-design` → `ddd-architecture` → 5축 스킬 → `testing-junit` → `observability` → `k6-load-testing` → `capacity-planning` → `documentation`

작업 중 매 추가/추상화 결정은 `scope-discipline` §1 결정 트리를 통과해야 한다.
