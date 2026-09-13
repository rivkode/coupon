# Load Test — 병목 분석 (Server A 1,000 TPS)

> **2026-08-27 전면 재측정.** 이전 판(2026-05-09)의 2 차 측정 결론 "server-b 처리량 한계" 는
> 근거가 무효였고, 재측정으로 **반증**됐다. 무엇이 무효였고 무엇으로 대체됐는지는 §1 · §5 에 있다.
> 원본 산출물: `load-test/experiments/results/` (회차별 k6 출력 / 부하 구간 Prometheus 지표 /
> 부하 중 컨테이너 CPU 샘플 / 적용 설정 근거), 실시간 랩 노트: `load-test/experiments/RUNLOG.md`.

---

## 0. 결론 요약

| 질문 | 답 | 근거 |
|---|---|---|
| server-b 가 병목인가? | **아니다** | 어떤 조건에서도 B 내부 p95 **9~43 ms**, CPU max 0.36~0.74. B 는 977 TPS 를 p95 9 ms 로 처리했다 |
| MySQL 이 병목인가? | **아니다** | 부하 **중** MySQL-A CPU 27~39 % (CPU 제한 없음). 커밋 fsync 를 꺼도 처리량·pool 대기 모두 그대로 |
| 재시도 증폭(H1)이 원인인가? | **아니다** | `max-attempts=3` 에서도 재시도 발동 **0 회**. B 가 받은 요청은 A 진입보다 **적다**(증폭의 반대) |
| 스케줄러 되먹임(H2)이 원인인가? | **아니다** | 스케줄러를 완전히 끄고도 개선 없음. cycle 은 fixed-delay 안에 들어옴(mean 0.18~0.22 s) |
| Kafka(H3)가 원인인가? | **아니다** | publish mean 4~6 ms, producer 버퍼 여유 33 MB/33 MB, `request_latency_avg` ≤ 1.4 ms |
| **그럼 무엇인가?** | **server-a 의 CPU 포화 + `HikariCP pool=50` 이 만든 동시성 증폭** | pool=10 → 503 0 건, pool=50 → 503 대량. 임계값·retry·스케줄러는 모두 무관 |
| 현재 코드는 1,000 TPS 를 견디나? | **견딘다** | 기본 설정 steady-state **977~988 TPS, 실패 0 %, p95 37~40 ms** |
| 5 월 1 차 측정은 왜 81/s 였나? | **`@Transactional` 이 외부 HTTP 호출을 감싸 커넥션 보유 시간을 부풀렸다** | 복원 측정에서 보유 시간 5.0 → 14.6 ms, 획득 대기 0.4 → 727 ms, 처리량 988 → 661/s (§4-2) |

**한 줄 요약** — 2 차 측정의 붕괴는 "B 의 처리량 한계" 가 아니라 **"1 vCPU 인 A 에서 커넥션 풀을 50 으로
키워 동시 처리 수를 늘린 결과 CPU 경합이 폭증하고, 그 지연을 Circuit Breaker 가 'B 가 느리다' 로
오판해 503 을 대량 생산한 것"** 이다.

---

## 1. 이전 판의 2 차 결론이 왜 무효였는가

### 1-1. 근거 A — 컨테이너 CPU 표가 무효

이전 판은 아래 표로 "server-b 가 병목" 을 주장했다.

| 컨테이너 | CPU |
|---|---|
| server-a | 0.38 % |
| MySQL | 2.48 % |
| **server-b** | **14.95 %** |
| server-c | 6.39 % |

**이 표는 병목 판정에 쓸 수 없다.** 표 제목 그대로 "load 종료 시점" 의 `docker stats` **단일 스냅샷**이다.
`docker stats` 의 CPU% 는 100 % = 1 코어인데 `cpus=1.0` 인 server-b 가 14.95 % 라면 **0.15 코어만 쓰는
idle 상태**였다는 뜻이다. 포화의 증거가 아니라 **부하가 이미 끝났다는 증거**다.

재측정에서 부하 **중** 시계열로 보면 같은 자리에서 이렇게 나온다 (run3 rep1, 2 초 간격 14 샘플):

| 컨테이너 | avg | max | CPU 제한 |
|---|---|---|---|
| **server-a** | **96.0 %** | **106.6 %** | 1 코어 |
| mysql-a | 33.0 % | 53.6 % | **없음** |
| server-b | 31.7 % | 62.1 % | 1 코어 |
| server-c | 44.8 % | 54.5 % | 1 코어 |
| kafka | 18.0 % | 99.2 % | 2 코어 |

> 같은 표로 주장했던 "MySQL 은 병목이 아님" 은 **살아남는다.** 단 근거가 CPU 표가 아니라
> `커밋된 행 4,888 = ACCEPTED 수 일치` 이고, 재측정의 MySQL CPU 27~39 % 와 §5-6 이 이를 보강한다.

### 1-2. 근거 B — 나머지는 전부 A 쪽 증상

read-timeout 초과, Circuit Breaker OPEN, 503 11,191 건은 전부 **A 가 관측한 값**이라
"B 의 응답이 느렸다" 까지만 말한다. B 안에서 Redis 인지 Kafka 인지 CPU 인지는 하나도 구분하지 못한다.
그 구분을 위해 이번에 B 내부 계측을 새로 붙였다 (§2-3).

### 1-3. 데이터 자체가 "처리량 한계" 와 모순이었다

| 제시 부하 | ACCEPTED (60 s) | 초당 환산 | 조건 |
|---|---|---|---|
| 500 TPS | 27,851 | **464 / s** | pool=20, read-timeout 1 s |
| 1,000 TPS | 4,888 | 81 / s | pool=10, read-timeout 500 ms |
| 1,000 TPS | 4,056 | **68 / s** | pool=50, read-timeout 500 ms |
| 1,000 TPS | 9,603 | 160 / s | pool=20, read-timeout 1 s |

처리량 한계라면 464 근처에서 평평해져야 한다. 부하를 2 배로 올렸는데 **85 % 붕괴**했고 풀을 키우자 더
나빠졌다. 이건 capacity ceiling 이 아니라 **혼잡 붕괴(congestion collapse)** 의 서명이다.

### 1-4. 2 차 측정은 애초에 "재현" 할 수 없다 — 시점 모순

| 커밋 | 날짜 | 내용 |
|---|---|---|
| `c6ef265` | 2026-05-09 | **1~3 차 측정이 보고서에 커밋된 시점** |
| `71fd4b0` (#33) | 2026-05-10 | `feat(server-a): A→B 호출에 Resilience4j Retry 적용` — **retry 최초 추가** |

**2 차 붕괴 당시 server-a 에는 retry 가 존재하지 않았다.** 따라서 `server-a/application.yml` 주석의
"retry max-attempts=3 이 부하 증폭의 결정적 요인" 은 2 차를 설명할 수 없다.
또한 1/2 차는 `cc6de3b`(batch insert + Bulkhead + pool 50) → `43e4471`(진입 계층 재작성) 사이의
**작업 트리에서 측정된 것으로 보이며, 정확한 코드 상태는 재구성 불가능**하다 — 보고서에 측정 시점의
커밋 해시가 없기 때문이다 (재발 방지: §8).

→ 그러므로 아래 Run 2 는 "2 차 재현" 이 아니라 **"현재 코드 + 2 차 설정값"** 이다.

---

## 2. 재측정 방법

### 2-1. 환경

| 항목 | 값 |
|---|---|
| 호스트 | Mac M-series, Colima **6 CPU / 12 GiB** (이전 측정 환경으로 복원) |
| 컨테이너 자원 | server-a/b/c `cpus=1.0, mem=2g`, Kafka `cpus=2.0`. **MySQL / Redis 는 CPU 제한 없음** |
| 부하 | k6 `issue-1k-tps.js` — `constant-arrival-rate` 1,000 TPS × 60 s, maxVUs 500 |
| 재고 | `TOTAL_INVENTORY=1,000,000` — 매진 경로가 결과를 오염시키지 않도록 |
| 관측 | Prometheus scrape 5 s. **모든 수치는 부하 구간 시계열**, 앱 외 컨테이너는 부하 중 2 초 간격 `docker stats` |

> 작업 시작 시점의 VM 은 4 CPU / 8 GiB 였고 다른 프로젝트 컨테이너가 동작 중이었다. promotion 스택만으로도
> CPU 예약 합이 5.0 이라 초과 예약 상태였으므로, 측정 전에 6 CPU / 12 GiB 로 복원하고 외부 워크로드를 제거했다.

### 2-2. 절차

1. 설정은 **환경변수로만** 주입한다. `application.yml` 의 기본값은 한 줄도 바꾸지 않았다.
   resilience4j 인스턴스명 `couponIssuing` 이 camelCase 라 환경변수 relaxed binding 으로는 다른 인스턴스가
   생성되므로 `SPRING_APPLICATION_JSON` 을 사용했다 (`load-test/experiments/docker-compose.experiment.yml`).
2. 회차 사이에 restart 하지 않는다. 대신 **재기동 직후 warmup 부하 1 회를 흘려 버리고** 이후 3 회를 측정한다.
3. 회차 사이에 Kafka lag 을 0 까지 drain 해, 다음 회차가 이전 회차의 backlog 를 물려받지 않게 했다
   (각 회차 시작 시점 lag 은 `results/<run>/rep*.pre` 에 기록).
4. **한 번에 한 변수만** 바꾼다.

### 2-3. 이번에 새로 붙인 계측

이전에는 B 내부를 가를 수 있는 계측이 없었다 (Lettuce 메트릭은 Spring Boot 가 자동 바인딩하지 않는다).

| 메트릭 | 무엇을 가르나 |
|---|---|
| `redis.pending.op{op}` | Redis 왕복 구간 — accept 경로(`save_pending`) / 스케줄러 경로(`find_stale`) |
| `kafka.issue.publish{path}` | 동기 Kafka publish 블로킹 구간 — accept / scheduler |
| `pending.scheduler.cycle` | 스케줄러 한 cycle 이 `fixed-delay` 를 넘는지 |
| `pending.scheduler.lookup` | 스케줄러의 C internal GET 건당 시간 |
| `pending.scheduler.batch` | cycle 당 처리한 stale pending 건수 |

Grafana 대시보드도 코드와 맞췄다 — 존재하지 않는 메트릭 패널(batch queue, Bulkhead)과 가상 스레드
환경에서 의미 없는 `tomcat_threads_busy_threads` 를 제거하고, Kafka producer / consumer lag / Redis /
스케줄러 / retry / CB 패널을 추가했다.

---

## 3. 회차별 조건과 결과

모든 회차: 1,000 TPS × 60 s, warmup 1 회 후 3 회 측정. 표의 값은 회차별 원본이다.

| Run | 바꾼 변수 | ACCEPTED/s (rep1/2/3) | 503 (rep1/2/3) | p95 (rep1/2/3) | 판정 |
|---|---|---|---|---|---|
| **0** | 없음 (현재 기본값) | 885 / **977** / **988** | 0 / 0 / 0 | 782 / **40** / **37** ms | 기준선. 붕괴 없음 |
| **1** | 스케줄러 OFF | 855 / 898 / 866 | 0 / 4,879 / 0 | 803 / 191 / 808 ms | **H2 반증** |
| **2** | pool=50 + rt/slow-call=500ms + retry=3 | **437** / 683 / 645 | **25,335 / 15,014 / 17,768** | 953 / 797 / 681 ms | 붕괴 재현 |
| **3** | Run 2 에서 retry 만 =1 | 426 / 767 / 675 | 25,553 / 11,329 / 16,053 | 978 / 468 / 489 ms | **H1 반증** (Run 2 와 동일) |
| **4** | MySQL-A `flush_log_at_trx_commit=2` | 775 / 947 / 855 | 0 / 0 / 7,851 | 879 / 785 / 30 ms | **DB 커밋 비용 반증** |
| **5** | rt/slow-call=500ms **만** (pool=10) | 795 / 841 / 806 | **0 / 0 / 0** | 943 / 970 / 954 ms | 임계값만으론 붕괴 안 함 |
| **6** | **pool=50 만** (임계값 기본) | **446** / 777 / 741 | **20,319** / 32 / 5,217 | 1,154 / 1,160 / 1,079 ms | **원인 변수 확정** |
| **7** | `@Transactional` 복원 (측정용 일시 변경, 되돌림) | 690 / 661 / 695 | 0 / 0 / 0 | 836 / 852 / 810 ms | **1 차 측정의 메커니즘 확인** |

> Run 0~7 은 **A 진입 측의 붕괴 원인**을 가르는 회차다. 이후 **Run 8~10 (컨슈머 병렬도 × 재고 행 개수)** 과
> **매진 시나리오**는 발급 처리 측 용량을 재는 별도 축이며 §6-1 · §6-2 에 있다.

### 2×2 — 붕괴의 필요조건은 pool 크기다

| | 임계값 느슨 (rt 1,000 / slow-call 800 ms) | 임계값 타이트 (500 / 500 ms) |
|---|---|---|
| **pool = 10** | Run 0 — 503 **0 / 0 / 0** | Run 5 — 503 **0 / 0 / 0** |
| **pool = 50** | Run 6 — 503 20,319 / 32 / 5,217 | Run 2 — 503 25,335 / 15,014 / 17,768 |

**pool=10 이면 임계값을 어떻게 조여도 503 이 한 건도 없다. pool=50 이면 임계값과 무관하게 503 이 발생하고,
임계값이 타이트하면 매 회차 대량으로 발생한다.**

---

## 4. 진짜 원인 — CPU 포화 위에서 동시성을 키운 결과

측정으로 확인된 연쇄는 다음과 같다.

1. **A 는 1 vCPU 에서 이미 포화해 있다.** 어느 회차든 `process_cpu_usage` max 0.95~1.00.
   측정된 A 의 처리 용량은 **950~990 req/s** 이고 k6 는 1,000/s 를 밀어 넣으므로 **이용률 ρ ≈ 1** 이다.
2. **`pool=50` 은 이 상태에서 동시 처리 수를 5 배로 늘린다.** `hikaricp_connections_active` 가 실제로 50 을
   찍는다. 1 코어 위에서 JDBC/커밋 작업 50 개가 동시에 돌면 컨텍스트 스위칭과 CPU 경합이 늘어난다.
   `pool=10` 은 반대로 **자연스러운 유입 제한(admission control)** 로 작동해 CPU 를 보호한다 —
   초과 요청은 CPU 를 쓰지 않고 pool 큐에서 대기한다.
3. **경합이 A→B 호출 구간까지 늘어뜨린다.** Run 6 에서 B 의 서버 측 p95 가 14 ms → **43 ms** 로 올랐다.
   B 가 느려진 게 아니라 A 가 더 많은 동시 요청을 밀어 넣어 B 의 큐도 같이 깊어진 것이다 (B CPU 는 0.57~0.63).
4. **Circuit Breaker 가 그 지연을 "B 장애" 로 오판한다.** CB 가 재는 것은 A 자신의 포화까지 포함한
   end-to-end 호출 시간이다. 임계를 넘으면 OPEN → 후속 호출은 B 를 부르지도 않고 즉시 503 fallback.
5. **결과는 증폭이 아니라 차단이다.** Run 2 rep1 에서 A 진입 54,743 건 중 B 에 도달한 것은 26,948 건뿐이다.

> **왜 pool=10 에서는 p95 가 950 ms 인데도 CB 가 안 열리나** — `IssueRequestService.issue` 는
> **`client.issue(B)` 를 먼저 호출하고 그 다음에 `repository.save()`** 를 한다. 즉 HikariCP 대기는 B 호출
> **뒤에** 발생하므로 CB 가 재는 구간에 들어가지 않는다. 사용자 latency 의 대부분은 **CB 가 관측하지 못하는
> 구간**에 있다. 이는 자기 DB 지연으로 CB 가 열리지 않는다는 점에서 다행이지만, 동시에
> **CB 가 A 의 진짜 병목을 보지 못한다**는 뜻이기도 하다.

---

## 4-1. "풀 고갈" 을 두 원인으로 가르는 지표 — 커넥션 보유 시간

이전 판의 1 차 측정은 아래 로그로 **"DB 의 한계가 아니라 애플리케이션 계층의 풀 고갈"** 이라고 판단했다.

```
HikariPool-1 - Connection is not available, request timed out after 3011ms
(total=10, active=10, idle=0, waiting=489)
```

**여기까지는 맞다** — 실패 경계가 DB 안이 아니라 DB 앞에 있었다는 뜻이다.
그러나 그 다음 문장인 **"그러므로 풀이 작다"** 는 성립하지 않는다. 풀 고갈에는 원인이 둘 있고 증상이 같다.

| | 원인 | 풀을 키우면 |
|---|---|---|
| (a) 진짜 부족 | 뒤의 DB 는 더 받을 수 있는데 앞의 풀이 좁아 못 넘긴다 | 처리량이 는다 |
| (b) 보유 시간이 길다 | 커넥션을 쥔 스레드가 CPU 를 못 받아 일을 못 끝낸다 (풀 대기는 **증상**) | 더 나빠진다 |

두 경우를 가르는 지표는 **커넥션 보유 시간(`hikaricp_connections_usage`)** 이다. 이번에 측정했다.

| 회차 | pool | **보유 시간 mean** | 획득 대기 mean | 획득 실패 | Little's Law 이론 용량 | 실측 처리량 |
|---|---|---|---|---|---|---|
| run0 rep3 | **10** | **4.8 ms** | 1.2 ms | 0 | `10 / 0.0048` = **2,083/s** | **988/s** |
| run5 rep2 | 10 | 8.4 ms | 190 ms | 0 | 1,190/s | 841/s |
| run2 rep1 | **50** | 33.0 ms | 131 ms | 0 | 1,515/s | 437/s |
| run6 rep1 | **50** | **38.6 ms** | 134 ms | 0 | `50 / 0.0386` = **1,295/s** | **446/s** |

- **`pool=10` 의 이론 용량은 2,083/s 로 제시 부하(1,000 TPS)의 2 배다. 풀은 제약이 아니었다.**
- **풀을 5 배로 키우자 보유 시간이 8 배로 늘었다** (4.8 → 38.6 ms). 커넥션이 5 배가 돼도 이론 용량은
  1.6 배도 안 늘고, 실측 처리량은 **오히려 988 → 446 으로 반토막**났다. 전형적인 (b) 다.
- 즉 `waiting=489` 는 "커넥션이 부족하다" 가 아니라 **"커넥션을 쥔 쪽이 느리다"** 의 신호였다.

### 2 차 측정은 이미 (a) 를 반증하고 있었다

풀을 10 → 50 으로 키웠을 때 접수 성공이 **4,888 → 4,056 으로 줄고** 실패율이 **49.86 % → 74.33 % 로 올랐다.**
가설 (a) 가 맞다면 이런 결과는 나올 수 없다 — 풀이 제약이었다면 넓혔을 때 처리량이 늘어야 한다.
그 데이터는 "가설이 틀렸다" 는 신호였는데 **"server-b 가 따라오지 못했다"** 로 읽혔고, 그때 원인이 B 로
옮겨갔다. 이번 재측정이 반증한 것이 바로 그 결론이다.

---

## 4-2. 1 차 측정이 81/s 였던 이유 — `@Transactional` 이 외부 호출을 감쌌다

### 코드 이력

측정 당시(`c6ef265`, 2026-05-09) `IssueRequestService` 는 이랬다.

```java
@Transactional                                       // ← 35 행
public IssueOutcome issue(IssueCommand cmd) {
    IssueAcceptanceResult result = client.issue(...); // ← 37 행: 외부 HTTP 호출이 트랜잭션 안
    IssueRequest req = repository.save(...);          // ← 39 행
    ...
}
```

이 `@Transactional` 은 **2026-08-18 (`ed27658`, PR #44)** 에서야 제거됐다 — 즉 1~3 차 측정 전체가
**"A 가 B 로의 HTTP 왕복이 끝날 때까지 DB 커넥션을 쥐고 있는"** 코드에서 이뤄졌다.

### 측정으로 확인 (Run 7 — 측정용 일시 변경, 되돌림)

현재 코드에 `@Transactional` 만 되돌리고 나머지는 기본값으로 두고 측정했다.

| | run0 rep2/rep3 (`@Tx` 없음) | **run7 rep2 (`@Tx` 복원)** |
|---|---|---|
| **커넥션 보유 시간 mean** | **5.0 / 4.8 ms** | **14.6 ms** (2.9 배) |
| **커넥션 획득 대기 mean** | **0.4 / 1.2 ms** | **727 ms** (약 1,800 배) |
| B 서버 측 mean | 5.7 / 5.5 ms | 6.6 ms (거의 동일) |
| 처리량 | 977 / 988 /s | **661 /s** |
| p95 | 40 / 37 ms | 852 ms |

**보유 시간이 늘어난 폭이 곧 B 호출 시간**이다 — 커넥션이 B 왕복 동안 점유된다는 직접 증거다.

Little's Law 로 환산하면 두 상태가 갈리는 이유가 분명해진다.

| 상태 | 보유 시간 | 풀 이론 용량 (`pool 10 / 보유시간`) | CPU 천장 | **실제 병목** | 실측 처리량 |
|---|---|---|---|---|---|
| `@Tx` 복원 | 14.6 ms | **685 /s** | ≈ 980 /s | **커넥션 풀** | 661 /s |
| `@Tx` 없음 (현재) | 5.0 ms | **2,000 /s** | ≈ 980 /s | **A 의 CPU** | 977~988 /s |

**`@Transactional` 이 있으면 풀 용량이 CPU 천장 아래로 내려와 풀이 병목이 되고, 없으면 풀 용량이
CPU 천장 위로 올라가 CPU 가 병목이 된다.** 병목이 사라진 것이 아니라 **옮겨간** 것이다.

### 5 월의 81/s 는 여기에 "느린 B" 가 곱해진 결과

1 차 측정 ACCEPTED 4,888 / 60 s = **81.5/s**, pool=10 → 역산 보유 시간 **123 ms**.
현재 코드에 `@Transactional` 만 되돌렸을 때는 14.6 ms 였다. 차이는 **B 의 응답 시간**이다.
보유 시간 = B 호출 + 트랜잭션 오버헤드 + 커밋 이므로, 123 ms 가 되려면 B 호출이 **약 115 ms** 여야 한다.
당시 read-timeout 이 500 ms 였으니 그 범위 안이다.

> **단정하지 않는다** — 5 월 B 의 실제 응답 시간은 **확인할 수 없다.** 그때 B 내부 계측이 없었기 때문이며
> (이것이 이 보고서 전체가 다시 쓰인 근본 이유다), 1 차 측정 시점의 코드 상태도 재구성 불가능하다(§1-4).
> 확인된 것은 **메커니즘**(외부 호출이 트랜잭션 안에 있으면 보유 시간이 그만큼 늘어난다)과
> **오늘의 크기**(보유 5.0 → 14.6 ms, 처리량 988 → 661/s)까지다.

### 이 결합이 곧 혼잡 붕괴의 고리다

```
B 응답 느려짐 → 커넥션 보유 시간 ↑ → 풀 용량 ↓ → 요청 적체 → 동시 in-flight ↑ → B 더 느려짐 → …
```

`@Transactional` 이 **"B 의 지연" 과 "A 의 풀 용량" 을 직접 묶어버린 것**이다. 종료 조건이 없다.
그 상태에서 풀을 10 → 50 으로 키우면 고리가 더 빨리 돌 뿐이라, 2 차 측정의 "풀을 키웠더니 더 나빠졌다" 가
설명된다. **현재 코드는 이 결합이 끊겨 있어서, B 가 느려져도 A 의 풀 용량은 그대로다.**

> Run 7 의 `@Transactional` 은 **측정용 일시 변경이며 되돌렸다** (`git checkout` 후 재빌드·재기동 확인).

---

## 5. 가설별 판정

### H1 — A 의 재시도 증폭: **반증**

주장: "A 가 read-timeout 으로 끊어도 B 는 끝까지 처리하므로 요청 1 건이 B 에 최대 3 개의 in-flight 를
만들고 B 부하가 3 배가 된다."

| 반증 근거 | 값 |
|---|---|
| `max-attempts=3` 에서 재시도 발동 | `successful_with_retry=0`, `failed_with_retry=0` (Run 2, 3 회 전부) |
| B 가 받은 요청 수 | A 진입의 **49~72 %** — 3 배는커녕 증가가 없다 |
| retry 3 → 1 로 낮췄을 때 (Run 3) | 붕괴가 사라지지 않음. Run 2 와 같은 분포 |

재시도가 발동하지 않는 원인 추정(코드 근거, **직접 검증하지 않음**):
`RestClientCouponIssuingClient.issue` 에 `@Retry` 와 `@CircuitBreaker(fallbackMethod=...)` 가 함께 붙어 있고
resilience4j 기본 aspect 순서가 `Retry( CircuitBreaker( fn ) )` 이므로, CB 가 예외를 삼켜 fallback 을
반환하면 바깥 Retry 는 "성공" 으로 관측한다. **관측된 사실은 "재시도가 발동하지 않는다" 까지다.**

→ `server-a/application.yml` 의 "retry max-attempts=3 이 부하 증폭의 결정적 요인" 주석은 근거가 없다.

### H2 — PendingIssueScheduler 되먹임 루프: **반증**

스케줄러를 완전히 정지시키고(cycle 0 회, republish 0, C internal lookup 0 건) 측정했으나 **개선이 없었다**
(Run 1: 855/898/866 vs Run 0: 885/977/988).

가설이 예측한 수치와 실측이 다르다:

| 항목 | 가설의 예측 | 실측 |
|---|---|---|
| C internal GET 건당 | 50 ms | **3~4 ms** |
| cycle 시간 | 2.5 s (fixed-delay 초과 → 연속 실행) | mean **0.18~0.22 s** (fixed-delay 1 s 안) |
| 재발행 | 초당 50 건 | 60 초 구간에 **38~145 건** |

> **적용 범위** — 이 반증은 60 초 부하 · pending 수천 건 조건에 한정된다. cutoff(10 s)를 넘긴 pending 이
> 수만 건 쌓이는 장시간 부하에서는 batch(50)를 계속 채우게 되므로 재검증이 필요하다. **측정하지 않은 구간이다.**

### H3 — Kafka 동기 publish: **이 부하 구간에서는 반증**

| 지표 | 값 |
|---|---|
| `kafka.issue.publish` (accept) mean | 4~6 ms |
| `kafka_producer_request_latency_avg` max | 0.65~1.41 ms |
| `kafka_producer_record_queue_time_avg` max | 4.1~5.3 ms |
| `kafka_producer_buffer_available_bytes` min | **33 MB / 33 MB — 고갈 없음** |

다만 코드 상 위험은 남아 있다 — `IssueRequestPublisher.publishWithTimeout` 의 `.get(3000ms)` 는
`send()` **이후**만 감싼다. producer 버퍼가 차면 `send()` 자체가 `max.block.ms` 동안 블로킹하고,
server-b 기동 로그 확인 결과 이 값은 **미설정 기본값 60,000 ms** 다. 이번 부하에서는 버퍼가 차지 않아
발현되지 않았을 뿐, **더 높은 부하나 브로커 장애에서는 발현 가능한 미설정 구간**이다.

### H4 — 1 vCPU 를 세 워크로드가 공유: **부분 성립하나 B 에서는 아님**

B 에서는 성립하지 않는다 — B 의 CPU 는 어떤 조건에서도 max 0.36~0.74 로 여유가 있었다.
반면 **A 에서는 성립한다.** A 는 요청 처리만으로 1 코어를 다 쓴다 (§6).

### H5 (추가) — A→B 에도 커넥션 풀이 없다: **미분리, 기여는 작아 보임**

프롬프트가 스케줄러(B→C)에서 지적한 `SimpleClientHttpRequestFactory` 결함이 **A→B 에도 그대로 있다**
(`RestClientConfig`). JFR 프로파일에서 `sun.net.www`(=`HttpURLConnection`) 는 CPU 의 **5.0 %** 를 차지한다.
무시할 수는 없으나 지배적이지 않다. **이 변수만 분리한 회차는 돌리지 않았다 — 미측정이다.**

### H6 (추가) — DB 커밋 내구성 비용: **반증**

MySQL-A 에 `innodb_flush_log_at_trx_commit=2` + `sync_binlog=0` 을 적용해 커밋마다의 fsync 를 제거했다
(적용 확인: `SELECT @@innodb_flush_log_at_trx_commit` → 2).

| | Run 0 (기본) | Run 4 (fsync 완화) |
|---|---|---|
| 처리량 | 885 / 977 / 988 | 775 / 947 / 855 |
| **HikariCP pending max** | 474 / 5 / 32 | **468 / 459 / —** |
| mysql-a CPU avg | 33 % | **27~32 %** |
| server-a CPU avg | — | **90~98 %** |

**MySQL CPU 는 내려갔는데 처리량도 pool 대기도 개선되지 않았다.** 커넥션 대기는 DB 가 느려서가 아니라
**A 가 CPU 를 못 받아 커넥션을 오래 쥐고 있어서** 발생한다. 저장소 종류를 바꿔도 이 구조는 바뀌지 않는다.

---

## 6. server-a 의 CPU 는 어디로 가는가 (JFR 프로파일)

`JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=settings=profile` 로 Dockerfile 무수정 프로파일링.
부하 구간 60 초, 실행 샘플 **868 개** (샘플 1 개 ≈ 0.12 % → **1 % 미만은 노이즈**).
귀속 규칙: leaf 부터 올라가며 JDK 내부가 아닌 첫 프레임의 라이브러리.

| 구간 | 비중 |
|---|---|
| org.springframework 전반 | **30.2 %** |
| (JDK 내부만) | 12.0 % |
| Tomcat (tomcat + catalina + coyote) | **12.1 %** |
| com.mysql (JDBC 드라이버) | **10.7 %** |
| io.micrometer (메트릭 기록) | 6.0 % |
| org.hibernate | 5.3 % |
| com.fasterxml.jackson | 5.2 % |
| `sun.net.www` (= `HttpURLConnection`, B 호출) | 5.0 % |
| sun.nio | 3.9 % |
| ch.qos.logback (요청당 로그) | 2.9 % |
| io.lettuce (Redis) | 1.7 % |
| io.github.resilience4j | 0.9 % |
| **com.promotion (비즈니스 코드)** | **0.6 %** |

Spring 30.2 % 내역: `web.servlet` 4.0 %, `core.annotation` 4.0 %, `util.LinkedCaseInsensitiveMap` 3.9 %,
`http.converter` 2.2 %, `core.ResolvableType` 1.6 %. leaf 1 위는 `HashMap.getNode` 4.7 %,
2 위 `StringLatin1.toLowerCase` 3.2 % — **HTTP 헤더 맵의 대소문자 무시 처리**를 가리킨다.

**단일 핫스팟이 없다.** CPU 는 "요청 하나를 프레임워크로 처리하는" 비용에 고르게 흩어져 있고
비즈니스 코드는 0.6 % 다. 가상 스레드는 블로킹 동안 스레드를 점유하지 않게 해줄 뿐 **CPU 작업 자체를
없애지 않는다.** 요청당 약 1 ms 의 CPU 가 들고 1 코어는 초당 1,000 ms 뿐이므로,
**≈1,000 req/s 에서 CPU 100 % 는 이상 현상이 아니라 정상적인 한계**다.

> 한계: JDK 21 의 JFR 실행 샘플러는 캐리어 스레드를 샘플링한다. 이 표는 **CPU 를 쓰는 구간의 분포**이지
> 대기 시간 분포가 아니다.

---

## 6-1. 발급 처리 측 용량 — 컨슈머 병렬도 × 재고 행 개수 (run8 / run9 / run10)

"C 의 `concurrency=1` 은 근거가 있는 값인가" 를 확인하기 위해 두 변수를 교차했다.
`KAFKA_CONSUMER_CONCURRENCY` 를 override 로 주입했고 (`@KafkaListener` 애노테이션이 읽는 유일한 knob),
재고 행은 이벤트 수로 조절했다 (`issue-1k-tps-multi.js` + `run-integrated-multi.sh` 신규).

| 조건 | 스레드 | 건당 처리 | **C 처리량** | 스레드 이용률 | C CPU max |
|---|---|---|---|---|---|
| 재고 행 1, 스레드 1 (run0, 현재 기본값) | 1 | 6.8 ms | **141 건/s** | 0.96 | 0.48 |
| 재고 행 1, 스레드 3 (run8) | 3 | **14.9 ms** | 187 건/s | 0.93 | 0.75 |
| 재고 행 10, 스레드 1 (run9) | 1 | 7.0 ms | 135 건/s | 0.94 | 0.56 |
| 재고 행 10, 스레드 3 (run10) | 3 | 8.8 ms | **321 건/s** | 0.94 | 0.92 |

1. **스레드가 1 개면 행 개수는 무관하다** (141 vs 135). 기다릴 상대가 없으므로 경합 자체가 없다.
2. **행이 하나면 스레드 3 배가 +33 % 뿐이다.** 건당 처리 시간이 2.2 배로 늘고, 늘어난 8 ms 가 전부
   `SELECT ... FOR UPDATE` 대기다.
3. **행을 나누면 병렬화가 산다** (135 → 321 건/s, 2.4 배). 2 의 손실은 전적으로 행 락 경합이며 Kafka 병렬도
   문제가 아니다.
4. **병목이 이동한다.** 321 건/s 지점에서 C 의 CPU 가 0.92 로, 다음 제약은 1 코어다.
5. **비용이 있다.** C 를 빠르게 만들면 같은 호스트의 A 가 밀린다 (988 → 781 건/s, −21 %). A 와 C 가 분리된
   환경이면 사라질 비용이므로 로컬 단일 머신 측정의 한계다.

> 파티션이 3 개이므로 `concurrency=3` 이 현재 토픽의 최대 병렬도다. 컨슈머 3 개가 partition 0/1/2 를 하나씩
> 소유하는 것을 확인했다. 더 올리려면 파티션부터 늘려야 한다.

### H2 반증의 유효 범위를 정하는 수치

| | 속도 |
|---|---|
| 스케줄러 검사 속도 | batch 50 / cycle 약 1.2 초 = **약 42 건/s** |
| C 처리 속도 | **141 건/s** |

스케줄러는 ZSET 을 오래된 순으로 훑고 C 도 오래된 것부터 처리하므로, **C 가 3 배 빨라 스케줄러는 항상
"이미 처리된 구간" 만 본다.** 그래서 조회하면 대부분 결과가 있고 재발행하지 않는다 (실측 republish 0~186,
give_up 0). → **H2 반증의 조건은 "C 처리 속도 > 약 42 건/s" 다.** 그 아래로 떨어지면 스케줄러가 미처리 구간에
진입해 재발행을 시작하고, 그 재발행이 C 부하를 더 키운다.

---

## 6-2. 명세 조건의 매진 시나리오

`run-soldout.sh` 신규. CLAUDE.md §1/§2 조건 그대로 — **재고 100 장, 사용자 1,000 명, 1,000 TPS × 10 초**.
처리량이 아니라 **큐 유입량과 결론까지의 시간**을 재는 것이 목적이다.

| 지표 | 값 |
|---|---|
| A 가 실제로 받은 요청 | 3,430 (k6 가 6,570 건 dropped) |
| **매진 단락 (ADR-011 캐시)** | **2,808 (82 %)** |
| 큐 진입 = C 소비 | **579** |
| 사용자가 받은 "접수 완료" | 97 |
| 503 | 510 |
| **SUCCESS** | **100** (재고 잔여 0) |
| SOLD_OUT / FAILED | 479 / **0** |
| **결론까지 시간** | **1 초** |

1. **매진 캐시가 큐 유입을 94 % 줄인다.** 최악 상정 (10,000 건 ÷ 141 건/s = 71 초) 과 달리 실제 유입은 579 건이고
   부하 종료 1 초 뒤 전부 결론이 났다. **명세 조건에서는 `concurrency=1` 로 충분하다.**
2. **재고 정합성은 완전하다.** SUCCESS 정확히 100, 재고 잔여 0, FAILED 0.
3. **캐시는 매진된 뒤에만 작동한다.** 재고 100 장이 소진되기까지 약 1.7 초 동안은 모든 요청이 B 로 간다.
   그 구간이 이 시나리오에서 부하가 가장 몰리는 지점인데 캐시가 도와주지 못한다 (p95 3.12 초, k6 dropped 6,570).

---

## 7. 처방

### 7-1. 지금 코드에 대해

| 항목 | 판단 |
|---|---|
| `HIKARI_POOL_SIZE=10` (현재 기본값) | **유지.** 1 vCPU 에서 유입 제한으로 작동한다. 키우면 Run 2/6 처럼 붕괴한다 |
| `read-timeout=1000ms`, `slow-call=800ms` | **유지.** 조여도 pool=10 에서는 문제없으나 여유가 없을 이유도 없다 |
| `retry max-attempts=2` | **동작하지 않는다.** 유지해도 무해하지만 "작동 중" 이라고 믿으면 안 된다 (§5 H1) |
| Kafka `max.block.ms` | **미설정(60 s).** 현재 부하에선 미발현이나 설정값을 명시하는 편이 낫다 |

### 7-2. A 의 처리량을 더 올리려면 (측정된 비중 순, 이번 작업 범위 밖)

| 수단 | 근거 | 기대 |
|---|---|---|
| **A 수평 확장** | CPU-bound → 인스턴스 수에 거의 선형 | 가장 확실 |
| `percentiles-histogram: true` 축소 | Micrometer 6.0 % | 수 % |
| 요청당 `log.info` 제거/샘플링 | Logback 2.9 % | 수 % |
| `SimpleClientHttpRequestFactory` → 풀 있는 클라이언트 | `sun.net.www` 5.0 % + TCP 재수립 | 수 % |

다 합쳐도 10~15 % 수준이며 **2 배가 되지 않는다.** 저장소 교체(예: MongoDB)는 이 표 어디에도 해당하지
않는다 — JDBC 드라이버 10.7 % 를 다른 드라이버로 바꾸는 것일 뿐이고, MySQL 서버 자체는 CPU 제한이
없는데도 한 코어의 1/3 만 쓰며 놀고 있다 (§5 H6).

---

## 8. 측정 중 발견한 결함 (미수정)

### CB fallback 의 간헐적 500

```
java.lang.IllegalAccessException: class io.github.resilience4j.spring6.fallback.FallbackMethod
  cannot access a member of class ...RestClientCouponIssuingClient with package access
```

`RestClientCouponIssuingClient.issueFallback` 이 **package-private** 이라, fallback 이 동시에 대량 호출될 때
reflection 접근에 간헐적으로 실패한다. 한 측정 구간에서 **fallback 5,604 건 중 22 건(0.4 %)** 이 이 예외로
실패해 사용자에게 **503 대신 500** 이 나갔다.

- 처방: `issueFallback` 을 `public` 으로. **측정 회차 간 비교 가능성을 깨지 않기 위해 이번 작업에서는
  고치지 않았다.**
- 이전 판이 2 차 "500 559 건" / 3 차 "500 978 건" 을 "pool timeout 등" 으로 단정한 부분은 이 결함과
  구분되지 않은 상태였다.

---

### 사용자 응답과 실제 처리가 어긋난다

매진 시나리오에서 **579 건이 파이프라인에 들어갔는데 "접수 완료" 를 받은 사용자는 97 명뿐이었다.**
차이 약 482 건은 A 가 read-timeout 으로 호출을 끊었지만 **B 는 그 요청을 끝까지 처리한** 경우다.

- 정합성은 깨지지 않는다. 재시도하면 `HSETNX` 가 걸려 DUPLICATE(200) 이고, 폴링하면 결과를 확인할 수 있다.
- 그러나 **503 은 "일시 장애이니 재시도하세요" 인데 실제로는 접수가 끝난 상태다.** SUCCESS 100 건 중 일부는
  503 을 받은 사용자에게 발급됐을 가능성이 높다.
- 처방 후보: A 가 timeout 으로 끊었을 때 503 대신 "접수 여부 불명 — 결과를 조회하라" 는 응답을 주거나,
  B 의 접수를 A 의 응답과 분리해 사용자에게 항상 조회 경로를 안내한다. **이번 작업에서는 고치지 않았다.**

---

## 9. 측정하지 않은 것 (단정하지 않는다)

- **장시간 부하에서의 스케줄러 되먹임** — H2 반증은 60 초 부하 한정이다.
- **H5(A→B 커넥션 풀 부재) 단독 격리** — JFR 상 5 % 라는 것만 알고, 이 변수만 바꾼 회차는 없다.
- **pool 10 과 50 사이 값** (20, 30) — `infra-sizing.md §8.2` 에 500 TPS 에서 잰 결과는 있으나
  1,000 TPS 에서는 10 과 50 만 쟀다.
- **Kafka `max.block.ms` 발현 조건** — 버퍼가 차는 부하를 만들지 않았다.
- **회차 간 편차의 원인** — 같은 설정에서 p95 가 37 ms 와 800 ms 사이를 오간다. ρ ≈ 1 에서의 큐잉으로
  설명되지만, 무엇이 도착률/처리율을 흔드는지(MySQL dirty page flush 주기, `issue_request` 테이블 증가,
  JIT/GC 상태)는 측정하지 않았다.

---

## 10. 재발 방지

이번 재측정이 필요했던 이유는 결론이 틀려서가 아니라 **근거를 재현할 수 없게 기록했기 때문**이다.

1. **부하 종료 후 스냅샷을 병목 근거로 쓰지 않는다.** 병목 판정은 부하 **구간의 시계열**로만 한다.
   앱은 Prometheus, 앱 외 컨테이너는 부하 중 `docker stats` 샘플링 (`results/<run>/rep*.dockerstats`).
2. **설정값을 문서에 옮겨 적지 않는다.** `application.yml` / `docker-compose.yml` 을 인용하고, 실효값은
   런타임 근거(컨테이너 env, 기동 로그의 `ConsumerConfig`, consumer group 파티션 소유)로 남긴다
   (`results/<run>/config.txt`). 이번에 발견된 문서-코드 불일치는 §11 참조.
3. **측정 결과에는 코드 상태를 함께 남긴다.** 최소한 커밋 해시. 이번 판의 산출물은
   `results/<run>/config.txt` 에 적용 설정을, 이 문서에 측정 일자를 남긴다.
4. **한 회차에 한 변수만 바꾼다.** 이번 Run 2 는 4 개를 동시에 바꾼 조건이라, Run 5/6 으로 분리하기
   전까지는 어떤 변수가 원인인지 말할 수 없었다.
5. **계측이 없는 구간은 "모른다" 라고 쓴다.** 이전 판이 "server-b 처리량 한계" 라고 단정할 수 있었던 것은
   B 내부 계측이 없었기 때문이다.

---

## 11. 함께 정정한 문서-코드 불일치

| 위치 | 이전 기술 | 실제 |
|---|---|---|
| `traffic.md §5` | HikariCP 최종값 **20** | **10** (`application.yml` 의 `${HIKARI_POOL_SIZE:10}`) |
| `rate-limiting.md`, `infra-sizing.md` | consumer `concurrency 3 / max-poll-records 50` | **listener 스레드 1 개 / max-poll-records 50**. `docker-compose` 의 `SPRING_KAFKA_LISTENER_CONCURRENCY=3` 은 `@KafkaListener(concurrency=...)` 애노테이션에 밀려 **무효** — consumer group 조회에서 인스턴스 1 개가 partition 3 개를 모두 소유 |
| `rate-limiting.md §3.3` | "≈ 21 req/s/instance" | **철회 후 측정값으로 대체** — 스레드 1 개 기준 **141 건/s** (건당 6.8 ms). 산식의 `평균 처리 시간 0.1s` 는 미측정 가정이었고 `concurrency 3` 은 실효값이 아니었다 (§6-1) |
| `infra-sizing.md` | "인스턴스당 median 499 RPS → 3 대" | **폐기 후 재산정** — 1,000 TPS 부하에서 **977 req/s → 2 대**. 499 는 500 TPS 부하의 달성치라 상한이 아니었고, 그 측정은 `@Transactional` 이 외부 호출을 감싼 코드 상태였다 |
| `traffic.md §6` | "`issue-500-tps.js` 500 TPS 안정 / 1,000 TPS 일부 에러" | 1,000 TPS × 60 초 **3 회 모두 실패 0 %** |
| 여러 보고서 | "1 vCPU MySQL" | `docker-compose.yml` 에서 **MySQL / Redis 에는 CPU 제한이 없다.** 제한은 앱(`cpus:1.0`)과 Kafka(`cpus:2.0`)뿐 |
| `docs/decisions/README.md` | ADR-011 누락 | 추가 |
| `docker/prometheus/prometheus.yml` 주석 | retention 2h | 실제 flag 는 **24h** |
| Grafana 대시보드 | 존재하지 않는 메트릭 패널(batch queue / Bulkhead), 가상 스레드에서 무의미한 `tomcat_threads_busy_threads` | 제거 + 가설 구분용 패널 추가 |

---

## 부록 — 재현 방법

```bash
# 기준선
RUN_ID=run0 RUN_DESC="baseline" ./load-test/experiments/run-experiment.sh

# 변수 격리 예 (pool 만)
RUN_ID=run6 HIKARI_POOL_SIZE_A=50 ./load-test/experiments/run-experiment.sh

# 회차 비교표
python3 load-test/experiments/summarize.py

# 컨슈머 병렬도 × 재고 행 (run8~10) — concurrency 는 server-c 컨테이너에 KAFKA_CONSUMER_CONCURRENCY 로 주입
EVENTS=10 ./load-test/experiments/run-integrated-multi.sh

# 명세 조건 매진 시나리오
./load-test/experiments/run-soldout.sh

# server-a CPU 프로파일
./load-test/experiments/profile-server-a.sh
```
