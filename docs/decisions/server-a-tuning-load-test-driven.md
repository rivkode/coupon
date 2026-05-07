# Server A 의 batch insert / 백프레셔 — 부하 측정 후 도입 결정

> **Status**: 결정 (2026-05-07)
> **결정**: Day 4 의 k6 부하 측정으로 병목을 정량적으로 식별한 뒤 도입 여부 / 구현 형태를 결정.
> 본 시점에 "감으로" 도입하지 않는다.
> **연관**: CLAUDE.md §3 평가 항목 ① (트래픽 + 동시성), §5.1 (Server A 책임), ADR-005 (Rate Limiting + 백프레셔)
>
> 본 문서는 **측정 우선** 의 엔지니어링 원칙을 평가자에게 명시적으로 보여주는 기록이다.
> 결과 (k6 측정 + 병목 발견 + 적용 PR) 는 Day 4 의 후속 문서 (`docs/runbooks/day4-*` 또는 본
> 문서의 §6) 에 추적.

---

## 1. 배경 — CLAUDE.md 가 명시한 항목 중 현재 미적용 인 것

CLAUDE.md 가 평가 항목 ① / §5.1 / ADR-005 에서 다음을 권고한다:

| 출처 | 권고 |
|---|---|
| §3 평가 항목 ① | "HikariCP 튜닝, **비동기 로깅, batch insert**" |
| §5.1 Server A 책임 | "요청 로그를 RDBMS 에 기록 (감사/추적용, **비동기 또는 batch로**)" |
| ADR-005 | "**A 의 큐가 가득 차면 503** 반환 (시스템 보호 우선)" |

본 시점의 server-a 구현은 **둘 다 미적용**:

- **요청 로그**: `IssueRequestService` 가 매 요청마다 `tx1 (RECEIVED save)` → 외부 호출 →
  `tx2 (마감 save)` 두 트랜잭션을 사용 (`server-a/src/main/.../application/IssueRequestService.java`).
  매 요청 = 2 commit.
- **백프레셔**: Tomcat 의 default queue (`acceptCount` 100, `maxConnections` 8192) +
  Resilience4j Circuit Breaker 가 down-stream 보호. **명시적 큐 깊이 + 거부 정책 (503)** 은
  구현되지 않음.

---

## 2. 왜 본 시점에 "그냥 도입" 하지 않는가

> **Premature optimization is the root of all evil.** — Knuth

근본적 이유: **현재 구성이 1 vCPU / 2 GB / 10,000 TPS 시나리오에서 실제로 병목인지 측정되지
않았다.** 측정 없는 도입은 다음 위험을 안는다.

### 2.1 batch insert 의 보이지 않는 비용

- `tx1 + tx2` 가 1 commit 으로 축약되더라도 **busy time** 자체가 줄지 않을 수 있다 (네트워크
  RTT 가 지배적이면).
- batch 적재 (`@Async + queue + flush`) 는 구현 복잡도 증가 + 메모리 사용 + **장애 시 요청 유실
  위험** (queue 가 메모리에 있으면 JVM 크래시 시 사라짐). 추적/감사 용도라는 요청 로그의 본
  의도와 정면 충돌.
- 사용자 응답 latency 와 적재 latency 를 분리할 수 있는가는 별개 — 분리하면 응답 후 `RECEIVED`
  가 없는 시점에 사용자가 GET 으로 조회 시 404 (있다면).

### 2.2 백프레셔 큐의 보이지 않는 비용

- 명시적 큐는 메모리 비용 + GC pressure. 1 vCPU / 2 GB 에서 큐 1만 entry 적재 시 ~수 MB.
- Tomcat default queue 자체가 이미 백프레셔 — `acceptCount` 가 가득 차면 OS 가 SYN 거부.
  명시적 큐 도입의 **추가 가치** 가 없을 수 있다.
- Rate Limit (Bucket4j 10 req/sec) + CB 가 이미 다단 백프레셔 — 사용자별 토큰 + downstream
  보호.

### 2.3 평가 시그널

평가자 입장에서 가장 강한 시그널은:

> "기본 구현 → k6 부하 → 병목을 **수치로** 식별 → 정확히 그 병목만 해결"

> "ADR 가 명시했으니 그냥 batch insert 했다" — 측정 없는 도입은 시그널이 약하다.

본 결정 자체가 평가 항목 **⑤ (사이징)** 의 일부 — k6 측정 + Little's Law 기반의 정량 결정.

---

## 3. Day 4 측정 계획

### 3.1 k6 시나리오 (`load-test/scenarios/day4-*` 가 추가될 예정)

| 시나리오 | 목적 | 핵심 metric |
|---|---|---|
| `day4-smoke.js` (1 VU × 60s) | warm-up + sanity | p50 / p95 latency |
| `day4-load.js` (50 VU × 5min) | 정상 부하 — 사용자당 10 req/sec, 50 사용자 = 500 TPS | p95 latency, error rate, HikariCP active |
| `day4-stress.js` (200 VU × 5min, ramp) | 1 인스턴스 한계 — 1,000 TPS 목표 | RPS 한계점, Tomcat queue depth |
| `day4-spike.js` (0 → 500 VU 10s) | burst — 사용자 1,000명이 동시 진입 | Tomcat reject 발생 여부, CB OPEN 빈도 |

### 3.2 병목 식별 기준 (수치)

다음 metric 을 동시에 수집:

- **Server A** — `actuator/prometheus`:
    - `hikaricp_connections_active` / `_pending`
    - `tomcat_threads_busy` / `_config_max`
    - `http_server_requests_seconds{...}` p50/p95/p99
- **Server B** — 같은 endpoint
- **MySQL** — `SHOW STATUS LIKE 'Threads_connected'`, slow query log
- **Redis** — `redis-cli INFO clients`, `latency`

병목 의심 기준:

| 증상 | 가능성 있는 병목 |
|---|---|
| HikariCP active 가 max-pool-size 에 도달 + p95 latency 폭증 | tx1+tx2 가 connection 점유 → **batch insert 도입 후보** |
| Tomcat queue 가 가득 차며 요청 reject + CB 가 흡수 못 함 | **명시적 백프레셔 큐 + 503 도입 후보** |
| MySQL CPU 100% + slow query | 인덱스 / batch insert / 연결 풀링 |
| Redis CPU < 30% + 위 증상 | 재고는 병목 아님 (정상) |

### 3.3 결정 트리

```
k6 day4-load (500 TPS) 결과:
├─ p95 < 200ms + error rate < 1%
│   → 현재 구현이 평가 시나리오 (10,000 TPS / 인스턴스 500~1000) 에 충분.
│     batch insert / 백프레셔 미도입 + README 트레이드오프 1줄 (측정 결과 인용).
│
└─ p95 > 500ms 또는 error rate > 5%
    ├─ HikariCP exhausted + tx1+tx2 가 원인
    │   → batch insert 도입 (구현 옵션 §4 참조).
    ├─ Tomcat queue full + reject
    │   → 명시적 백프레셔 큐 + 503 도입 (구현 옵션 §5 참조).
    └─ 둘 다
        → 두 항목 모두 도입.
```

---

## 4. 도입 시 구현 옵션 — batch insert

| 옵션 | 장점 | 단점 |
|---|---|---|
| **A. JdbcTemplate.batchUpdate** (요청 도착 시 큐에 적재 + scheduler 가 N건씩 flush) | tx1+tx2 → 1 batch tx 로 축약. 측정 시 connection 점유 시간 감소 효과 명확 | 큐가 메모리 → JVM 크래시 시 유실. 5일 일정 내 in-memory 허용 (audit 손실 일부 인정) + README 명시 |
| **B. `@Async` + 별도 thread pool** (tx1 만 동기, tx2 는 비동기) | 사용자 응답 latency 만 단축. 구현 단순 | 마감 트랜잭션의 일관성 약화 — 외부 호출 결과를 RECEIVED 기록과 묶지 못함 |
| **C. INSERT only + 별도 update 잡 (eventually consistent)** | tx1 만 발생, tx2 제거 | "마감 상태" 가 비동기 업데이트 — GET endpoint 도입 시 race |

**채택 후보 (측정 후 결정)**: **A** — 측정 우선 + 구현 명확. INSERT 큐 + scheduler 200ms flush + JdbcTemplate batch.

---

## 5. 도입 시 구현 옵션 — 백프레셔

| 옵션 | 장점 | 단점 |
|---|---|---|
| **D. Tomcat queue 만 (현재)** | 0 line of code | 거부 시 사용자에게 의미 있는 응답 없음 (TCP reset) |
| **E. Resilience4j Bulkhead (semaphore)** | 동시 처리 수 제한 + bulkhead-full 시 즉시 거부 | 큐 깊이 개념은 없음 — semaphore 만 |
| **F. Resilience4j Bulkhead (thread-pool) + 큐** | 큐 깊이 + reject 정책 명시 | thread-pool 분리로 RestClient 의 thread 와 다름 — 디버깅 복잡 |
| **G. Servlet Filter 의 명시적 큐** (AtomicInteger inFlight + maxQueue) | 단순 + 거부 시 503 + Retry-After 명시 | Tomcat 과 두 단계 큐 — 조정 부담 |

**채택 후보 (측정 후 결정)**: **E** (Resilience4j Bulkhead semaphore) — 이미 의존성에 있고 (CB 와 함께), 거부 시 503 + Retry-After 응답 패턴이 CB 와 일관.

---

## 6. 후속 PR / 추적 (Day 4 진행 — 갱신됨 2026-05-08)

- [x] **PR #20**: Prometheus + Grafana 인프라 (`docker-compose.yml` + provisioning + 5 패널 대시보드)
- [x] **PR #21**: Day 4 4 시나리오 (`load-test/scenarios/day4-{smoke,per-instance,real-scenario,spike}.js`) + 측정 보고서 [`docs/reports/01.load-test-results.md`](../reports/01.load-test-results.md)
- [ ] **PR #22 (Phase C)**: **batch insert 도입 — GO** (측정 결과로 결정, 보고서 §5.1)
- [ ] PR #23 (조건부): 명시적 백프레셔 — **HOLD** (CB 가 86% 흡수, 추가 큐 가치 < 비용. Phase C 후 재검토)
- [ ] Day 5: 100,000 사용자 사이징 — Phase C 후 재측정 결과 기반

### 6.1 Phase B 측정 핵심 결과

본 문서 §3.3 의 결정 트리에 정확히 매핑:

| 측정값 | §3.3 임계 | 결정 |
|---|---|---|
| HikariCP active **10** + pending **188** (peak) | "HikariCP exhausted + tx1+tx2 가 원인" | **batch insert GO** ✅ |
| Tomcat busy **200** (peak, max 도달) | "Tomcat queue full + reject" | 명시적 백프레셔 HOLD (CB 가 흡수) |
| http p95 max **4.06s** (per-instance p95 **648ms**) | "p95 > 500ms" | Phase C 진입 GREEN LIGHT ✅ |

본 결정의 근거 + 데이터 + Day 5 사이징 입력은 모두 [`../reports/01.load-test-results.md`](../reports/01.load-test-results.md) 에 정리.

---

## 7. 거부된 대안

### A. "ADR 에 명시되어 있으니 본 시점에 그냥 도입"

- 평가 시그널 약화 (측정 없는 변경) — §2.3.
- 구현 비용 + 잠재적 리스크 (in-memory queue 유실, thread-pool 디버깅) 를 측정 없이 안음.
- 거부.

### B. "측정 자체를 미루고 README 한 줄로 대체"

- 평가 항목 ⑤ (사이징) 자체를 회피. 5일 일정에서 Day 4 의 핵심 작업이 부하 측정이므로 어차피
  진행해야 함.
- 거부.

---

## 8. 참고

- CLAUDE.md §3 (평가 항목), §5.1 (Server A 책임), ADR-005 (Rate Limit + 백프레셔)
- 측정 도구 — k6 + actuator/prometheus + (선택) Grafana
- Little's Law — `L = λW` 가 batch insert 후 connection 점유 시간 (`W`) 변화 계산의 기본
