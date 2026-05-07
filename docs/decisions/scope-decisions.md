# Scope 결정 — 의도적으로 안 한 것

> **Status**: 진화 (작업이 진행되며 추가됨)
> 5일 일정 안에서 평가 5축 (대량 트래픽 / 분산 정합성 / Hot Spot / Rate Limit / 사이징) 에 집중하기
> 위해 **명시적으로 미룬 항목**들의 종합. 무엇을 안 했고, 왜 안 했고, 운영이라면 어떻게 진화할지.

평가자에게 "시간 압박 안에서 의식적으로 우선순위를 정한 흔적" 을 보여주는 것이 본 문서의 목적.

---

## 1. 아키텍처 / 설계

| 항목 | 미적용 이유 | 진화 방향 |
|---|---|---|
| **헥사고날 아키텍처** | 5일 일정에 추상화 비용 > 가치. CLAUDE.md §10 안티패턴으로 명시. 가벼운 layered DDD (domain port + infrastructure adapter) 만 적용. | 도메인이 진화하며 어댑터가 늘어나면 검토 |
| **Saga Orchestrator** | Choreography 만으로 충분 (1 vCPU 부담 회피, ADR-002). 보상 트랜잭션도 Server B 에 한정. | 보상 흐름이 N단계가 되면 도입 |
| **CQRS / Event Sourcing** | 현재 read 모델 이 단순 (`coupon by code` / `coupons by user`). 이벤트 스토어 운영 비용 > 가치. | 분석 / 리포팅 워크로드가 본격화되면 |
| **Domain-Event 게시** | server-c 가 redeem 후 이벤트 발행 안 함. CLAUDE.md §5.3 의 redeem 책임이 "영구 저장 + 조회" 만. | 결제 / 주문 시스템 연동 시 Outbox + Kafka |

## 2. 멱등성 / 일관성

| 항목 | 미적용 이유 | 진화 방향 |
|---|---|---|
| **redeem 별도 idem 캐시** | 도메인 자체 멱등 (`used_at` 한 번 set) 으로 충분. 상세: [`redeem-idempotency-without-cache.md`](./redeem-idempotency-without-cache.md) | 다른 시스템 호출이 redeem 결과에 의존하면 |
| **CDC (Debezium) 기반 Outbox** | poller 방식이 5일 안에 구현 가능 + 평가 시그널 동등. ADR-002 가 "프로덕션 진화 방향으로 CDC" 명시. | 운영 — Outbox poller 의 polling overhead + 두 인스턴스 동시 발행 위험 회피 |
| **유령 재고 reconciliation** | server-b JVM crash (Lua 차감 후 / Outbox INSERT 전) 시 Redis 차감되었으나 MySQL 무. 본 과제는 README 트레이드오프로 인지만 명시. 상세: [`../runbooks/failure-modes.md`](../runbooks/failure-modes.md) | reconciliation job (Redis 발급 코드 vs Outbox diff) 또는 CDC 로 해결 |
| **Kafka Transactional Producer** | producer idempotence + Server C UNIQUE 만으로 의미적 exactly-once. transactional.id 추가 시 broker 부담 + Outbox 와 묶기는 별도 작업. | 다중 producer 또는 strict ordering 필요 시 |
| **Server C consumer DLT** | Spring Kafka DefaultErrorHandler 의 retry 후 stop. 운영 알림 + 수동 복구 가정. | 프로덕션 진화 — DLT + 알림 + replay |

## 3. 운영 / 모니터링

| 항목 | 미적용 이유 | 진화 방향 |
|---|---|---|
| **분산 트레이싱** | Day 4 부하 측정 + 사이징의 핵심 영역이 아님. MDC traceId 만 적용. | OpenTelemetry + Jaeger / Tempo |
| **구조화된 로깅 / 중앙 수집** | local 개발 / k6 검증 위주. 평가자가 grep 으로 추적 가능. | ELK / Loki + JSON 로그 |
| **Grafana 대시보드** | **PR #20 에서 인프라 도입** — docker-compose 에 Prometheus + Grafana 통합, 5 패널 (JVM heap / CPU / HTTP p95 / HikariCP / Tomcat busy) 자동 provisioning. Phase B 부하 측정 결과 캡처용. | 운영 — Alert Manager + multi-cluster + 분산 트레이싱 (Loki / Tempo) |
| **GitHub Actions CI** | 5일 일정 + 1인 작업이라 PR 수동 검증 (`./gradlew test`). | 다중 작업자 진입 시 즉시 도입 |
| **Helm / Kustomize** | docker-compose 만 제공. K8s 배포는 본 과제 외. | 운영 환경 진입 시 |

## 4. 보안

| 항목 | 미적용 이유 | 진화 방향 |
|---|---|---|
| **JWT 인증** | `X-User-Id` 헤더로 단순 식별 (CLAUDE.md §5.1 명시). 5일 일정에 인증 인프라는 과함. | API gateway + JWT validation |
| **HTTPS / TLS** | local 환경. 운영에서는 ALB / Ingress 가 종료. | nginx + Let's Encrypt 또는 ALB |
| **Rate Limit redeem** | server-c 직접 호출이라 server-a 의 Bucket4j 미적용. 본 과제 트래픽 모델이 발급 burst 위주. | redeem 도 server-a 라우팅 또는 별도 rate limit |
| **WAF / DDoS** | 인프라 영역, 본 과제 외. | CloudFront / Cloudflare |
| **Grafana / 관측 도구 자격 증명** | local 평가 가정 — `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` 환경변수로 override 가능 (default admin/admin). | 운영 — Secret Manager (AWS / Vault) + SSO 통합 |

## 5. 데이터 / 마이그레이션

| 항목 | 미적용 이유 | 진화 방향 |
|---|---|---|
| **운영 stock 분배 endpoint** | `StockSeeder` 빈 + `redis-cli` 직접 SET 만 제공. CLAUDE.md §10 의 admin endpoint 는 본 과제 외. | admin API + auth + audit |
| **이벤트 마스터 운영 도구** | `event` 테이블 INSERT 는 마이그레이션 시점 또는 cli 가정. CLAUDE.md §5.1 의 "운영 도구 영역" 명시. | admin 백오피스 |
| **보존 정책 / TTL** | server_a.issue_request 가 무한 누적. server_b.coupon_issue_outbox 도 동일. | partition by month + 보존 정책 (90일) |
| **Read replica** | 모든 read 가 primary. | 트래픽 증가 시 read replica + routing |

## 6. 테스트 / 품질

| 항목 | 미적용 이유 | 진화 방향 |
|---|---|---|
| **100% 커버리지** | CLAUDE.md §11 — "핵심 동시성 + 멱등성 집중, 100% 목표 X". | 운영 critical path 만 |
| **mutation testing** | 5일 일정 외. | PIT / Stryker |
| **chaos engineering** | local 환경 한계. | 운영 진입 시 |
| **performance regression in CI** | k6 시나리오는 정합성 회귀 위주. Day 4 부하는 측정 1회. | k6 cloud + threshold gate |

## 7. 평가자에게의 시그널

본 문서가 보여주는 메시지:

1. **"시간 압박을 인지** 하고 있다 — 모든 항목을 다 만들 수 없다는 것을 안다"
2. **"우선순위를 알고 있다** — 평가 5축 (CLAUDE.md §3) 에 자원을 집중하고 그 외는 의도적으로 미룸"
3. **"진화 경로를 알고 있다** — 운영 환경에서 어떻게 발전시킬지 구체적 구상 보유"
4. **"빠른 코드 + README 한 줄로 대체** 가 합리적인 영역과, **반드시 코드로 증명해야 할 영역** 을 구분"

5일 안에 모든 것을 다 못 만들지만, "무엇을 못 만들지" 를 명확히 선언하는 것이 평가 시그널의 일부.

---

## 8. 본 문서의 위치

본 문서는 작업이 진행되며 추가된다. 새 결정이 발생할 때마다 해당 카테고리에 1~2줄로 추가 + 별도
ADR 가 필요한 결정은 `decisions/` 폴더에 신규 문서로 분리.
