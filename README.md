# promotion

5일 일정의 백엔드 채용 과제. **콘서트 사전 예매 할인 쿠폰 — 선착순 10,000장** 시나리오를
1 vCPU / 2 GB RAM 노드 3대 (Server A / B / C) 로 처리하는 분산 시스템 구현입니다.

핵심 명제: 인프라 제약을 **소프트웨어(큐 + 비동기 + 백프레셔 + 분산 캐시)** 로 푼다.

## 구현 범위

- 발급 (Issue) — Server A 진입점, Idempotency-Key, Rate Limit, Circuit Breaker
- 재고 관리 — Server B Redis Lua atomic 차감, 샤딩 (Hot Spot 회피)
- 영구 저장 — Server C Kafka consume + UNIQUE constraint 멱등성
- 사용 (Redeem) — Server C 낙관적 락

## 빠른 실행

```bash
docker compose up -d
./gradlew clean build -x test
./gradlew :server-a:bootRun
```

## 문서

### 아키텍처

- [01. 아키텍처 개요](docs/architecture/01.Architecture-Overview.md)
- 02. 도메인 설계 (Day 2~3 작성 예정)
- 03. 시퀀스 다이어그램 (Day 2~3 작성 예정)
- 04. ERD / JPA 설계 (Day 2~3 작성 예정)
- 05. API 설계 (Day 2~3 작성 예정)
- 06. 설계 결정 / 트레이드오프 (Day 2~5 작성 예정)

### 보고서

- 01. 부하 테스트 결과 (Day 4 작성 예정)
- 02. 100,000명 사이징 계산 (Day 5 작성 예정)

## 검증 — Phase 9 E2E (k6)

Day 1 의 7개 시나리오 (정상 / 멱등 / cross-user / rate limit / 헤더 누락 / validation /
Circuit Breaker) 를 k6 스크립트로 자동화했습니다. 각 스크립트는 1 VU / 1 iteration / threshold
`checks rate==1.0` — assertion 한 건이라도 실패하면 exit code 가 0 이 아닙니다.

```bash
brew install k6                                    # macOS
docker compose up -d mysql redis                   # 인프라

# Stub profile (시나리오 1~6)
./gradlew :server-a:bootRun --args='--spring.profiles.active=local' &
./load-test/run-phase9-stub.sh

# Circuit Breaker (시나리오 7) — Server B 부재 + RestClient 활성화 상태 필요
kill $(lsof -ti:8080) 2>/dev/null
./gradlew :server-a:bootRun &
k6 run load-test/scenarios/phase9-07-circuit-breaker.js
```

자세한 사전 조건 / 결과 해석 / CI 통합 가이드는 [`load-test/README.md`](./load-test/README.md) 참조.

## 실행 정보

### 애플리케이션

- Server A: `http://localhost:8080/actuator/health`
- Server B: `http://localhost:8081/actuator/health`
- Server C: `http://localhost:8082/actuator/health`
- Prometheus: `http://localhost:8080/actuator/prometheus` / `http://localhost:8081/actuator/prometheus` / `http://localhost:8082/actuator/prometheus`

### 인프라

- MySQL `3306` (root / `rootpassword`, schemas: `server_a`, `server_b`, `server_c`)
- Redis `6379`
- Kafka `9092` (컨테이너 간 INTERNAL) / `29092` (호스트 HOST)
- Kafka UI `http://localhost:8085`

자세한 명령어와 검증 절차는 [01. 아키텍처 개요 §6 로컬 실행](docs/architecture/01.Architecture-Overview.md#6-로컬-실행) 참조.

## 핵심 설계 포인트

- A→B 동기 + B→C 비동기로 즉시 응답 + 영구 저장 분리 (ADR-001)
- Redis Lua 기반 atomic 재고 차감, 10 샤드로 Hot Spot 회피 (ADR-003)
- 이중 멱등성 방어: Server A `(user_id, idempotency_key) UNIQUE` + Server C `idempotency_key UNIQUE` (ADR-004)
- Bucket4j Lettuce backend, 사용자당 10 req/sec (ADR-005)
- redeem 낙관적 락 (`@Version`), 비관 락 회피 (ADR-007)

상세 근거는 [01. 아키텍처 개요 §5 핵심 설계 결정](docs/architecture/01.Architecture-Overview.md#5-핵심-설계-결정-adr) 참조.

## 진행 상황

### Day 1 — Server A 단독 동작 (완료)

| PR | 내용 | 상태 |
|---|---|---|
| #1 | Gradle 멀티모듈 + docker-compose | merged |
| #2 | 도메인 모델 + Flyway | merged |
| #3 | README 골격 + Mermaid | merged |
| #4 | docs/architecture/ 분리 | merged |
| #5 | Server A 발급 API + Stub 클라이언트 | merged |
| #6 | Idempotency + Rate Limit Filter | merged |
| #7 | RestClient + Circuit Breaker (+ k6 Phase 9) | merged |

### Day 2 — Server B (재고 + Outbox)

| PR | 내용 | 상태 |
|---|---|---|
| #8 | Server B 부트스트랩 + Outbox 인프라 | in progress |
| #9 | Redis Lua atomic 재고 차감 + `POST /internal/v1/coupons/issue` | 예정 |
| #10 | A↔B 실통합 + k6 day2 시나리오 | 예정 |

전체 5일 로드맵: [`CLAUDE.md` §12](./CLAUDE.md).
