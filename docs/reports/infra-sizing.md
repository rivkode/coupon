# 인프라 사이징

![system-design-dataflow](../photo/system-design-scale-out-sizing.png)


## 0. 요약

- **현재 인스턴스 (1 vCPU / 2 GB) 의 측정 처리량**: A 진입 기준 **median 499 RPS** (5 차례 반복 측정의 중간값, steady-state). cold start 시 285 RPS, warmup 후 ~500 RPS 로 수렴.
- **목표 부하**: **10 초에 1,000 명 사용자가 사용자당 100 건 → 10,000 TPS 평균**.
- **필요 인스턴스 수 (산식)**: `목표 TPS / (인스턴스당 처리량 × 가용률)` = `10,000 / (499 × 0.7)` ≈ **약 30 대** (가용률 70% 헤드룸 포함).
- **사전 증설** — 이벤트 시작 30 분 전 30 대로 미리 scale-out.

---

## 1. 측정 결과 — 현재 단일 인스턴스 처리량

### 1.1 측정 환경

| 항목 | 값 |
|---|---|
| 서버 사양 | 1 vCPU / 2 GB RAM (Server A / B / C 각각, docker compose `cpus=1` + `mem=2g`) |
| 부하 도구 | k6 |
| 측정 지표 | A 의 `POST /api/v1/coupons/issue-request` 가 **200 응답을 받은 비율** |
| 시나리오 | `load-test/scenarios/issue-500-tps-10s.js` (500 TPS × 10 초) |
| 호스트 | Mac M-series + Colima 6 CPU / 12 GiB |
| Kafka | 단일 broker (`cpus: 2.0`), partition 3 |

### 1.2 측정값 — 5 회 반복

같은 시나리오를 5 회 반복 측정하여 **중간값** 으로 사이징 산정.

| 회차 | 달성 RPS | p50 | p90 | p95 | avg | dropped | accepted | 503 | thresholds |
|---|---|---|---|---|---|---|---|---|---|
| 1차 | 285 | 957ms | 1,190ms | 1,270ms | 943ms | 1,972 | 100% | 0 | p95 미달 |
| 2차 | 448 | 534ms | 663ms | 701ms | 538ms | 350 | 100% | 0 | p95 미달 |
| 3차 | 499 | 27ms | 73ms | 82ms | 36ms | 0 | 100% | 0 | **PASS** ✅ |
| 4차 | 500 | 13ms | 18ms | 22ms | 15ms | 0 | 100% | 0 | **PASS** ✅ |
| 5차 | 499 | 13ms | 18ms | 20ms | 14ms | 0 | 100% | 0 | **PASS** ✅ |
| **median** | **499** | **27ms** | **73ms** | **82ms** | **36ms** | **0** | **100%** | **0** | **PASS** |

#### JIT warmup 효과 (1차 vs 3차~5차)

```
1차 (cold)        : 285 RPS, p95 1,270ms, 1,972 dropped iterations
2차 (warming)     : 448 RPS, p95   701ms,   350 dropped iterations
3~5차 (steady)   : ~499 RPS, p95  20-82ms,  0 dropped iterations
```

→ 부팅 직후 1~2 회 부하는 **HotSpot JIT 컴파일 / HikariCP 풀 워밍 / Kafka client 메타데이터 fetch** 의 cold path. **운영 capability 의 진짜 그림은 steady-state (3차~5차)**. 본 사이징의 baseline 으로 median 499 RPS 사용.

> 측정값은 "사용자가 200 ACCEPTED 를 받았다" 는 사용자 관점 기준. C 의 비동기 발급 처리량은 별도 — 결과는 폴링으로 확인되므로 사용자 입장의 throughput 은 A 진입의 200 비율로 결정.

---

## 2. 목표 부하 정의

### 2.1 CLAUDE.md §2 시나리오 재진술

| 항목 | 값 |
|---|---|
| 총 사용자 (피크) | 1,000 명 |
| 사용자당 요청 | 10 초 내 100 건 |
| **평균 TPS** | **10,000 TPS** (1,000 × 100 / 10) |
| 데이터 형식 | JSON 10 필드 |
| 프로토콜 | HTTPS |

### 2.2 목표 / 현재 / 갭

| 구분 | 값 |
|---|---|
| 목표 TPS | **10,000** |
| 현재 인스턴스 1 대 처리량 (median, steady-state) | **499** |
| 처리 갭 | **약 20 배** |

→ 단일 인스턴스로는 도달 불가. 수평 확장 필요.

---

## 3. 인스턴스 수 산정 (Little's Law 변형)

### 3.1 산식

```
필요 인스턴스 수 = 목표 TPS ÷ (인스턴스당 처리량 × 가용률)
```

- **가용률 (utilization target)**: 70% — 100% 까지 풀로 쓰면 burst / queue 폭주 위험. p95 안정 영역에 헤드룸 30% 확보.

### 3.2 계산

```
필요 인스턴스 수 = 10,000 ÷ (499 × 0.7)
              = 10,000 ÷ 349.3
              ≈ 30 대
```

> 보수적 표기 — **약 20 대가 이론적 최소** (`10,000 / 499 ≈ 20`), 가용률 70% 헤드룸 고려해 **30 대 권장**. cold start 보호용 buffer 를 더 두려면 35 대까지 검토.

### 3.3 가용률을 70% 로 두는 이유

| 이유 | 설명 |
|---|---|
| **Burst 흡수** | 평균 10,000 TPS 라도 순간 piking 은 그 이상. 100% 풀로 잡으면 spike 시 즉시 latency 악화 |
| **장애 인스턴스 흡수** | 50 대 중 일부 (5–10대) 가 죽거나 GC pause 등으로 처리 못 해도 나머지가 받음 |
| **Little's Law 의 큐 비선형성** | 가용률이 100% 에 가까워질수록 큐 길이가 비선형 폭증 (M/M/1 큐 이론) |
| **배포 / 롤링 업데이트 여유** | 일부 인스턴스가 재시작 중이어도 트래픽 처리 가능 |

---

## 4. 운영 전략

### 4.1 사전 증설 (Pre-warming)

이벤트 시작 30 분 전에 인스턴스를 30 대로 미리 scale-out + warmup 부하 (예: 50 RPS × 30 초) 흘려 JIT/풀 워밍.

- ✅ **장점**:
  - 트래픽 spike 시점에 인스턴스가 모두 ready 상태 — JVM warmup / 커넥션 풀 초기화 / 캐시 워밍 완료.
  - **HPA 의 scale-out 지연 (수십 초 ~ 수 분) 회피** — 급증 트래픽 spike 가 시작된 후 늘리면 늦음.
- ❌ **단점**:
  - 평시 / 이벤트 외 시간에도 비용 발생 (이벤트 시작 30 분 전 ~ 종료까지).
  - 이벤트 일정이 정해져 있을 때만 적합 — 비예측 트래픽엔 부적합.

### 4.2 HPA (Horizontal Pod Autoscaler) — 검토

CPU / 사용자 정의 메트릭 기준 자동 scale-out.

- ✅ **장점**:
  - 평시에는 작게 운영, 부하 증가 시 자동 확장 → 비용 효율.
  - 비예측 트래픽 spike 에도 대응.
- ⚠️ **검토 필요사항**:
  - **scale-out 지연** — Pod 부팅 + JVM warmup + 커넥션 풀 초기화 (수십 초). 본 도메인의 spike 속도와 맞는지.
  - **메트릭 선정** — CPU 만으론 부족할 수 있음. Kafka consumer lag / DB 커넥션 풀 사용률 등 도메인 메트릭 활용 검토.
  - **Custom metric 연동** — Prometheus + Custom Metrics API 로 도메인 메트릭 노출 필요.
  - **상한 / scale-in 정책** — 무한 scale-out 방지 (max replicas), scale-in 시 처리 중 메시지 안전성 (graceful shutdown).

### 4.3 두 전략의 조합 (권장)

| 시나리오 | 전략 |
|---|---|
| 예고된 이벤트 (시작 시간 정해진 캠페인) | **사전 증설 (30 대 + warmup 부하)** — spike 시점에 ready + steady-state 진입 보장 |
| 비예측 트래픽 / 평시 운영 | **HPA** — 비용 효율적 자동 확장 |
| 둘의 조합 | 사전 증설 baseline + HPA 가 추가 spike 흡수 |

---

## 5. 거부한 사이징 방식

### 거부 1 — 측정 없이 추정으로 사이징

"1 vCPU 면 대충 N TPS 하겠지" 식의 estimate.

- ❌ **거부 이유**: 인스턴스당 실제 처리량은 도메인 / 트랜잭션 / 외부 의존성에 따라 천차만별. **측정이 권위**.

### 거부 2 — 단일 시점 측정만으로 결정

한 번 부하 테스트 결과만으로 사이징.

- ❌ **거부 이유**: 단일 측정은 환경 / 워밍업 / 외부 변수에 흔들림. 본 보고서는 **5 회 반복 측정 후 중간값** 을 사이징 baseline 으로 사용.
- JIT warmup 효과로 1차/2차는 cold path (285/448 RPS), 3차~5차는 steady-state (499/500/499 RPS) 로 명확히 분리됨. 둘 다 의미가 있어 함께 명시 — 운영 baseline 은 steady-state, cold start 보호 buffer 는 1차 측정 기준.

### 거부 3 — 가용률 / 헤드룸 미고려

`10,000 / 285 ≈ 35 대` 만 계산하고 끝.

- ❌ **거부 이유**: 100% 가용률 가정은 실제로 **장애 0 / GC 0 / spike 0** 인 이상적 환경에서만 성립. 실운영에서는 30% 헤드룸이 사실상 필수.

---

## 6. 결정 요약

| 항목 | 값 |
|---|---|
| **현재 인스턴스 1 대 처리량** | **median 499 RPS** (5 회 반복, steady-state, 200 응답 기준) |
| cold start 시 처리량 | ~285 RPS (warmup 미완료) |
| **목표 TPS** | 10,000 |
| **이론적 최소 인스턴스** | 20 대 (`10,000 / 499`) |
| **권장 인스턴스 (가용률 70%)** | **30 대** (`10,000 / (499 × 0.7)`) |
| **운영 전략 (예고 이벤트)** | 시작 30 분 전 사전 증설 + warmup 부하 |
| **운영 전략 (비예측 트래픽)** | HPA — 적용 가능성 검토 |
| **적용된 튜닝** | Colima 6 CPU/12 GiB, Kafka cpus=2, listener.concurrency=3, max-poll-records=50, batch-size=32K, HikariCP=30, 가상스레드 |
| **추가 검토** | hot row 락 경합 측정 (장시간 부하), Kafka broker 다중화 (단일 broker 가 진정한 ceiling) |

---

## 7. Server A HikariCP pool 최적값 측정

### 7.1 배경 — 왜 측정이 필요했나

HikariCP wiki 의 "magic formula" 는 **데드락 방지 최소값** 산정용:

```
pool size = Tn × (Cm − 1) + 1
  Tn : DB 커넥션을 동시에 요청할 수 있는 thread 최대 개수
  Cm : 하나의 task 가 동시에 보유하는 connection 수
```

본 시스템의 적용:

| 인스턴스 | Tn (동시 thread) | Cm (동시 보유 connection) | 공식 결과 |
|---|---|---|---|
| Server-A | virtual thread per request — 부하 시 ~수백 | 1 (단일 `@Transactional` audit commit) | `N × 0 + 1 = 1` |
| Server-C | listener=3 + outbox=1 + redeem ≈ 5~10 | 1 (afterCommit 은 tx 종료 후 fresh connection) | `10 × 0 + 1 = 1` |

→ 공식은 **본 시스템에서 1 connection** 으로 데드락 안 남음을 알려줄 뿐, **throughput 사이징은 별도 측정** 필요. 또 다른 wiki 권장 (`core_count × 2 + spindles ≈ 3`) 은 CPU-bound DB 가정이라 본 시스템 (DB CPU 0.5%, 네트워크 RTT 위주) 에 부적합.

### 7.2 측정 방법

- 시나리오: `issue-500-tps-10s.js` (500 TPS × 10 초)
- 매 케이스: `docker compose up -d server-a` (cold restart) → warmup 50 건 → 사전 부하 1회 → 본 측정 부하 + HikariCP 메트릭 1초 폴링
- 메트릭: `/actuator/prometheus` 의 `hikaricp_connections_{active,idle,pending,max}`
- 비교 변수: A pool 만 변경 (3, 10, 20, 30, 40, 50). C pool 은 10 고정

### 7.3 6 케이스 결과

| pool | 달성 RPS | p95 latency | peak active | avg active | utilization | avg pending | 평가 |
|---|---|---|---|---|---|---|---|
| 3 | 173 | 1,910 ms | 3 / 3 | 2.3 | 100% (saturate) | 198 | wiki CPU-bound 권장 — 본 시스템 부적합 |
| 10 | 267 | 1,360 ms | 10 / 10 | 6.9 | 100% | 188 | 부족 |
| 20 | 335 | 977 ms | 20 / 20 | 12.7 | 100% | 165 | 부족 |
| **30** | **380** | **877 ms** | **30 / 30** | **20.6** | 100% | 166 | ⭐ **최적 — latency 가장 좋음** |
| 40 | 388 | 1,190 ms | 40 / 40 | 25.5 | 100% | 135 | RPS 약간 ↑, latency 악화 |
| 50 | 364 | 988 ms | 50 / 50 | 32.0 | 100% | 138 | **오히려 처리량 감소** (USL knee) |

> 본 측정은 매 케이스 cold restart 후 1회 warmup → 절대 RPS 는 §1 의 steady-state median (499) 보다 낮음. **케이스 간 상대 비교** 가 본 측정의 목적.

### 7.4 그래프 — RPS 와 p95 latency 의 pool 크기별 변화

```
RPS                         p95 latency (ms)
                            
  400 ┤        ●●           2000 ┤●
      │       ●  ●               │ ●
  350 ┤      ●    ●          1500 ┤   ●
      │     ●               1000 ┤      ●●●  ●
  300 ┤    ●                     │
      │   ●                      │
  250 ┤  ●                       │
      │ ●                        │
  200 ┤●                         │
      └──┬──┬──┬──┬──┬──┬           └──┬──┬──┬──┬──┬──┬
         3 10 20 30 40 50              3 10 20 30 40 50
                                                    pool
   sweet spot: pool 30~40        sweet spot: pool 30
```

- 3 → 30: **RPS +120%** (병목 해소 단계적)
- 30 → 40: RPS +2% (한계 효용 거의 0)
- 40 → 50: **RPS −6%** (역효과)

### 7.5 핵심 발견

#### 모든 pool 에서 100% saturate

- peak active = pool max (전 케이스). spike 시점에 항상 가득 사용
- avg active = 64~69% (평소 70% 활용 + spike 흡수)
- avg pending 130~200 (항상 100~200 thread 가 connection 대기)

→ **pool 을 무한정 늘려도 항상 saturate**. connection 자체가 한계가 아니라 **들어오는 트래픽이 처리 capacity 를 항상 초과**.

#### pool=50 에서 처리량 감소 — Universal Scalability Law (USL) knee

이론적 원인:
- HikariCP 의 connection acquire latency 가 pool 크기에 비례 증가
- Connection 관리 overhead (validation, eviction, statement cache flush)
- DB 측 (MySQL-A 1 vCPU) 의 동시 connection 처리 한계
- USL 의 coherence cost — 더 많은 connection 이 conflict 비용 증가

→ pool 은 **유한값에서 최적**. 50 은 본 환경에서 그 한계를 넘은 지점.

#### virtual thread 의 효과

- pool 30 환경에서 **항상 165 thread 가 connection 대기**
- platform thread 였다면 165 × ~1 MB stack = 165 MB + context switch 비용으로 무너졌을 것
- virtual thread 라 비용 무시 + connection release 가 빠름 (commit RTT ~3ms) → 큐 빠르게 drain
- 즉 **HikariCP saturation 에도 응답 latency 가 sub-second 로 유지** — virtual thread 의 진짜 효과

### 7.6 측정 기반 권장

| 인스턴스 | 측정 기반 최적 | 본 보고서 적용값 | 근거 |
|---|---|---|---|
| **Server-A** | **30** | **30** | RPS/latency balance 최적. 40 은 throughput 약간 우위지만 p95 악화, 50 은 역효과 |
| **Server-C** | 5 ~ 10 | 10 | 어떤 부하에서도 peak active ≤ 3 (이전 별도 측정에서 확인). 안전 헤드룸 포함 10 |

### 7.7 결론

- **HikariCP wiki 의 magic formula 는 본 시스템에서 trivial 결과 (=1)** — Cm=1 이라 데드락 위험 자체가 없음. throughput 사이징 가이드 X
- **CPU-bound 권장 (=3)** 도 본 시스템 부적합 — DB I/O wait 가 압도적이고 virtual thread 가 고동시성 처리 가능
- **측정으로 결정**: A pool **30** 이 RPS / latency / 자원 활용률의 균형점
- **진짜 ceiling** 은 connection pool 이 아니라 **A→B sync 호출 + MySQL-A 1 vCPU commit 처리량**. pool 더 늘려도 RPS 향상 안 됨 (오히려 감소)

[← README](../../README.md)
