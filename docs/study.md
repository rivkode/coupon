# 과제 관련 스터디

> 본 과제를 수행하며 **처음 학습한 내용들** 을 정리합니다.
> 결정의 근거가 된 학습 / 실측 / 검증의 흔적이며, 결과물 (설계 문서 / 기술 보고서) 의 출처가 된 노트입니다.

[← README](../README.md)

---

## 1. Connection Timeout vs Read Timeout

### 학습 동기

A → B HTTP 호출에 Resilience4j Circuit Breaker 와 함께 timeout 을 설정해야 했는데, "그냥 적당히 3 초 / 5 초" 가 아니라 **왜 그 값인지** 근거가 필요했다.

### Connection Timeout — TCP 연결을 맺는 시간의 상한

TCP 연결은 **3-way handshake** 로 맺어진다.

```
Client                  Server
  | ── SYN ─────────▶    |
  | ◀──── SYN+ACK ──     |
  | ── ACK ─────────▶    |
        연결 완료
```

각 단계마다 패킷 유실 가능. 유실되면 **재전송** 해야 하는데, 첫 재전송까지 기다리는 시간이 **InitRTO (Initial Retransmission Timeout)**.

- **Linux 의 InitRTO 기본값 = 1 초**
- 즉 SYN 패킷이 한 번 유실되면 연결 맺기까지 **최소 1 초 + α** 가 걸림
- 두 번 유실되면 RTO 가 지수적으로 backoff (1s → 2s → 4s ...)

따라서 connection timeout 을 **너무 짧게** (예: 500ms) 잡으면 정상적인 패킷 1 회 유실에도 실패. 적당한 값:
- 초기 1 회 유실 흡수: 1s + α
- 한 번 더 흡수까지: ~3s

### Read Timeout — 응답 도착을 기다리는 상한

연결이 맺어진 뒤, **서버가 응답을 보내기까지** 기다리는 시간. 본 과제에서 Server B 의 응답은 Redis HSETNX + Kafka publish 까지 — 정상 latency 는 ms 단위.

→ **read timeout 1 초** 면 서버가 응답을 못 줄 때 빠르게 fail-fast.

### 본 과제 적용 값

| 항목 | 값 | 근거 |
|---|---|---|
| Connection Timeout | **3 s** | InitRTO 1s × 2 회 유실 흡수 |
| Read Timeout | **1 s** | B 의 정상 응답이 ms 단위, 길게 잡을 이유 없음 |

→ 두 timeout 의 의미가 다르므로 **다른 값** 을 잡아야 한다는 게 핵심 깨달음. 처음에는 둘 다 비슷한 의미인 줄 알았다.

---

## 2. HikariCP 풀 사이즈 — 공식 vs 실측

### 학습 동기

우아한 형제들 기술블로그에서 본 공식:

```
pool size = Tn × (Cm − 1) + 1
```

- Tn: 동시 실행 thread 수
- Cm: 한 트랜잭션이 동시에 잡는 connection 수

이 공식대로 설정해 봤는데 **RPS 가 기대만큼 나오지 않음**. 직접 측정해 봤다.

### 측정 결과

| 케이스 | A pool | C pool | RPS | p95 | A active (peak/avg) | A pending (peak/avg) | C active (peak/avg) | C idle (avg) |
|---|---|---|---|---|---|---|---|---|
| case1: HikariCP 공식 (=3) | 3 | 3 | **173** | 1.91 s | 3 / 2.3 | 298 / 197.9 | 3 / 1.6 | 1.4 |
| case2: 권장안 (=10) | 10 | 10 | **267** | 1.36 s | 10 / 6.9 | 290 / 187.9 | 3 / 1.5 | 8.5 |
| case3: 현재 세팅 (=20) | 20 | 20 | **335** | 977 ms | 20 / 12.7 | 280 / 164.7 | 2 / 1.4 | 18.6 |

→ 공식대로 (=3) 은 173 RPS 에서 멈춤. 풀을 늘릴수록 RPS 와 p95 가 동시에 개선.

### 관찰 1 — 모든 케이스에서 A 의 풀이 100% 포화

- A active 가 항상 pool 한도를 꽉 채움 (3 → 10 → 20 모두)
- 즉 **A 의 풀이 throughput 의 직접 제약** — 더 늘리면 더 받아낼 수 있음
- 실제로 pool=30 까지 올렸을 때 ~500 RPS 가능했음

### 관찰 2 — C 는 항상 여유

- C active 평균 1~2, idle 8.5 ~ 18.6
- **C pool=5 면 충분**. C 가 병목이 아님 — 진짜 병목은 A 의 HikariCP

### 관찰 3 — A 의 평균 pending 165~198 인데 응답이 1-2초 이내 인 이유

A 의 pending = "connection 을 기다리는 thread 수". 평균 200 가까이 항상 대기 중인데, 응답은 977ms ~ 1.91s 사이.

→ **virtual thread 의 효과**. virtual thread 라 thread 자체 비용이 거의 없고 (stack 메모리 / context switch 거의 무시), connection release 가 commit RTT 단위로 빠르게 일어나기 때문에 **큐가 빠르게 drain**.

→ 만약 platform thread 였다면 200 thread × stack memory + context switch 비용으로 무너졌을 것. **virtual thread 의 진짜 효과는 이런 워크로드에서 드러난다**.

### 결론

- **공식은 출발점일 뿐** — 실측이 권위.
- **풀 크기는 클라이언트가 아니라 서버 처리 한계에 맞춰야** 한다 (DB CPU 한계 / 락 처리량 등).
- virtual thread 환경에서는 thread 수가 아니라 **DB 의 active connection 처리량** 이 진짜 한계.

---

## 3. Kafka At-Least-Once — `acks=all` 만으로는 부족하다

### 학습 동기

처음에는 `acks=all` 만 켜면 끝인 줄 알았는데, **producer / consumer 양쪽 모두 추가 세팅** 이 필요했다.

### Producer 세팅

| 옵션 | 값 | 의미 |
|---|---|---|
| `acks` | `all` | 모든 in-sync replica 가 받았을 때만 ack — 데이터 유실 1 차 방어 |
| `enable.idempotence` | `true` | producer 재시도 시 메시지 중복 publish 방어 (sequence number 기반) |
| `retries` | `5` | 일시 장애 시 자동 재시도 |
| `max.in.flight.requests.per.connection` | `5` | idempotence 와 함께 쓸 때 안전 상한 |
| `linger.ms` | `5` | 짧게 묶어 throughput 개선 (low-latency 우선이라 5ms) |
| `delivery.timeout.ms` | `30000` | 전체 전송 데드라인 (재시도 포함) |

### Consumer 세팅

| 옵션 | 값 | 의미 |
|---|---|---|
| `enable-auto-commit` | `false` | 자동 commit 비활성화 — 처리 완료 후 명시적 ack |
| `ack-mode` | `RECORD` | 메시지 처리 완료마다 ack (배치 ack 아님) |
| `max-poll-records` | `50` | 한 번 poll 당 메시지 상한 (throttle) |
| `auto-offset-reset` | `earliest` | 초기 offset 위치 |
| `isolation-level` | `read_committed` | 트랜잭션 commit 된 메시지만 consume |

### 핵심 깨달음

- `acks=all` 은 **broker 측 보장**. producer 자신의 재시도 로직 / 멱등성 / 시간 상한은 별도로 켜야 한다.
- consumer 의 `enable-auto-commit=false` 가 빠지면 **처리 전에 offset 이 진행** 돼 메시지 유실. `acks=all` 의 의미가 무너진다.
- 비즈니스 로직이 끝난 뒤에 Consumer 가 commit 한다. 비즈니스 로직이 실패했는데 commit 해버리면 유실이 발생할 수 있다.
- "End-to-end at-least-once" 는 **producer + broker + consumer 세 단의 합작**.

---

## 4. Outbox 패턴 + 스케줄러 — 시스템 디자인 관점의 정합성

### 학습 동기

처음에는 Outbox 를 "어디서나 publish 안전하게 하려면 쓰는 패턴" 정도로만 알았다. 하지만 본 과제에서 **언제 쓰고 언제 안 써야 하는지** 가 더 중요한 학습이었다.

### Outbox 가 의미 있는 조건

| 조건 | 본 케이스 (Server C 발급 트랜잭션) |
|---|---|
| 변경 매체가 RDB 인가? | ✅ MySQL-C |
| publish 실패 시 정합성 깨지는가? | ✅ B 의 Redis 가 PENDING 으로 남음 |
| 트랜잭션 안에서 publish 하는 게 위험한가? | ✅ 1 vCPU 환경에서 트랜잭션 길어지면 락 큐 폭주 |

→ 세 조건이 모두 만족할 때만 Outbox 의 가치가 발현. **Server B 처럼 Redis 만 쓰는 곳에 Outbox 를 적용하면 의미 없는 RDB 의존이 추가될 뿐**.

### 스케줄러로 보완하는 영역

Outbox 가 적합하지 않은 곳 (Server B) 은 **스케줄러 + ZSET 인덱스** 로 보완.

- Redis ZSET 에 score = createdAt 으로 등록
- `@Scheduled` 가 1 초 주기로 ZRANGEBYSCORE 로 cutoff (10s) 초과 항목 batch fetch
- C 의 internal API 로 실제 처리 여부 검증 → 미처리면 Kafka 재발행
- cap (publishAttempts ≤ 3) 으로 영구 cycle 방지 → **30s SLA 보장**

### 핵심 깨달음

- **Outbox 는 패턴이지 만능 도구가 아니다**. 적용 조건을 따져야 한다.
- **회복 메커니즘은 매체 특성에 맞게 다르게** — RDB 는 Outbox, Redis 는 스케줄러.

---

## 5. 단건 INSERT 1,000 TPS — 오버엔지니어링 금지

### 학습 동기

처음에는 Server A 의 `issue_request` 적재를 **batch INSERT** 로 만들려고 했다. "트래픽이 많으니 모아서 한 번에 넣자" 는 직관.

### 측정 결과

- **단건 INSERT (per-request commit) 도 1 vCPU MySQL 에서 1,000 TPS 견딜 만함** — 경합이 없는 단순 INSERT 라.
- batch 를 도입하면:
  - 큐 + 플러시 정책 결정
  - 인스턴스 종료 시 in-memory 큐 손실 방어 로직
  - 백프레셔 처리
  - 디버깅 시 "내 요청은 어디 갔지?" 추적이 어려움

→ 트레이드오프가 큰 데 효익이 작음.

### 핵심 깨달음

- "트래픽이 많다 = 무조건 batch / 비동기 / 큐" 는 **오버엔지니어링**.
- 측정 먼저, 단순한 방법이 안 되는 게 확인된 다음에 복잡도 추가.
- "단순함이 우선" 이 (per-request commit) 으로 정착.

---

## 6. 비동기로 끊는 지점에 대한 고민

### 학습 동기

A → B → C 흐름에서 **어느 지점부터 비동기로** 처리할지가 시스템 디자인의 핵심 결정. 너무 빨리 끊으면 정합성 위험, 너무 늦게 끊으면 사용자 응답 latency 악화.

### 후보들

| 후보 | 결정 / 거부 |
|---|---|
| User → A 동기, A 부터 비동기 | ❌ A 가 응답해야 사용자가 진행하므로 너무 빠름 |
| User → A → B 동기, B 부터 비동기 | ✅ **채택** — Redis 적재 시점에서 at-least-once 보장 시작 |
| User → A → B → C 모두 동기 | ❌ C 의 비관적 락 대기가 사용자 latency 에 직접 영향 |

### 결정 근거

- **at-least-once 를 보장할 수 있는 가장 빠른 지점** 에서 끊기.
- Redis 는 영속 가능한 매체 + 빠른 쓰기 → "받았다" 를 즉시 기록 가능.
- Kafka publish 가 실패해도 Redis 가 살아있으면 스케줄러로 회복.

→ "비동기 경계 = 정합성 기준점" 이라는 시각이 새로웠다.

### 핵심 깨달음

- 비동기 경계는 **임의로 정하는 게 아니라 정합성 기준점이 결정** 한다.
- "사용자 응답 latency vs 정합성 강도" 의 트레이드오프를 의식적으로 결정해야 한다.

---

## 7. Redis 명령어 동작 원리

### 학습 동기

본 과제에서 Redis 의 다양한 자료구조를 처음 깊이 사용. 각 명령어의 의미와 시간 복잡도를 정리.

### 본 시스템에서 사용한 명령어

| 명령어 | 자료구조 | 시간 복잡도 | 용도 |
|---|---|---|---|
| `SET` | String | O(1) | 캐시 적재 (예: `event:{id}`, `coupon:available:{...}`) |
| `GET` | String | O(1) | 캐시 조회 |
| `EXISTS` | (모든 자료구조) | O(1) | negative cache 단락 체크 |
| `HSETNX` | Hash | O(1) | 중복 차단 + 신청 적재 (atomic) |
| `HSET` / `HGETALL` | Hash | O(1) / O(N) | 신청 상세 갱신 / 폴링 응답용 (N = field 수) |
| `ZADD` | Sorted Set | O(log N) | 스케줄러 work queue 등록 (score = createdAt) |
| `ZRANGEBYSCORE` | Sorted Set | O(log N + M) | cutoff 초과 항목 batch fetch (M = 결과 수) |
| `ZREM` | Sorted Set | O(log N) | 정상 처리된 항목을 work queue 에서 제거 |

[← README](../README.md)
