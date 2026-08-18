# 유량 제어


![system-design-dataflow](../photo/system-design-throttle.png)


## 0. 요약

- **결정**: Server B → Server C 사이에 **Kafka 를 backpressure buffer 로** 두고, **Server C consumer 측에서 처리 가능한 양만 poll** 하여 1 vCPU MySQL-C 를 보호.
- **핵심 파라미터** (Server C):

  | 설정 | 값 | 역할 |
  |---|---|---|
  | `spring.kafka.listener.concurrency` | **3** | partition 병렬 소비 — 단일 vCPU 안에서의 동시 처리 한계 |
  | `spring.kafka.consumer.max-poll-records` | **50** | 한 번 poll 당 가져오는 메시지 상한 — 처리 burst 제한 |
  | `spring.kafka.consumer.enable-auto-commit` | **false** | 메시지 처리 완료 후 명시적 commit — 처리 실패 시 재처리 가능 |

---

## 1. 문제 정의

대량 발급 트래픽 환경의 위험:

- **Server A** 가 평균 1,000 TPS 를 받음 → **Server B** 가 즉시 Redis 적재 + Kafka publish (수 ms)
- 그러나 **Server C** 의 발급 트랜잭션은 비관적 락 + INSERT 가 포함돼 **건당 100ms~ 단위**
- C 가 처리할 수 있는 양보다 더 빠르게 메시지가 쌓이면:
  - Kafka consumer 가 무제한 poll → C 가 한 번에 너무 많은 트랜잭션을 시작
  - 비관적 락 큐가 폭주 → DB 커넥션 풀 / Tomcat 워커 점유
  - 1 vCPU MySQL-C 의 처리 한계 초과 → p99 latency 폭주, 일부 트랜잭션 timeout

**핵심 원리** — 가장 느린 단계 (C 의 발급 트랜잭션) 가 시스템의 throughput 을 결정한다. 그보다 빠르게 흘려 보내면 큐만 길어질 뿐 throughput 은 늘지 않는다 (Little's Law).

---

## 2. 고려한 대안

### 대안 A — HTTP A↔B 사이 동기 backpressure (B 가 바쁘면 A 가 대기)

B 의 처리 가능 큐가 가득 차면 A 가 5xx 또는 대기.

- ❌ **거부 이유**:
  - A 는 사용자 응답 경로 — 대기시키면 latency 직접 악화.
  - B 자체는 가벼움 (Redis 적재 + Kafka publish 만) → backpressure 가 필요한 지점이 아님.
  - 진짜 병목은 **Kafka 너머 C** 인데, A 에 backpressure 두면 위치가 잘못됨.

### 대안 B — Consumer throttle 없이 무제한 poll (디폴트)

Kafka consumer 가 max 만큼 가져오고 가능한 빨리 처리.

- ❌ **거부 이유**:
  - 1 vCPU MySQL-C 가 동시 트랜잭션을 너무 많이 받게 됨 → 비관적 락 큐 폭주.
  - 처리 burst 가 커지면 lag 가 spike, 다음 poll 까지의 지연도 비예측적.

### 대안 C — Kafka consumer 파라미터로 throttle (채택)

C 의 listener / consumer 파라미터로 한 번에 처리할 양을 제한.

- ✅ **채택 이유**:
  - **병목 (C) 위치에서 직접 제어** — 진짜 처리 한계와 throttle 이 같은 지점.
  - **Kafka 가 자연스러운 buffer** — 초과 메시지는 Kafka partition 에 쌓이고 lag 으로만 보임. 데이터 유실 없음.
  - **사용자 응답 경로에 영향 없음** — A → B 까지는 평소 속도, 사용자는 200 ACCEPTED 받음.
  - 인프라 추가 없이 설정 한 줄 — 단순성.

---

## 3. 결정 — Kafka Consumer 파라미터 3 종

### 3.1 설정

```yaml
spring:
  kafka:
    listener:
      concurrency: 3                          # partition 병렬 소비
    consumer:
      max-poll-records: 50                    # 한 번 poll 당 메시지 상한
      enable-auto-commit: false               # 명시적 commit
      ack-mode: RECORD                        # 메시지 처리 완료마다 ack
```

### 3.2 파라미터 의미

#### `listener.concurrency = 3`

- 같은 consumer group 안에서 **3 개의 listener 스레드** 가 partition 을 병렬 소비.
- partition 수 ≥ 3 일 때 효과 (각 listener 가 다른 partition 담당).
- 1 vCPU 에서 너무 키우면 컨텍스트 스위칭 / DB 커넥션 풀 경합으로 역효과 → **3 이 1 vCPU 의 안전 상한**.

#### `consumer.max-poll-records = 50`

- 한 번 `poll()` 호출에서 가져오는 메시지 **최대 50 개**.
- listener 가 50 개를 처리한 뒤에야 다음 poll → 처리 burst 의 상한 형성.
- 너무 작으면 (예: 10) poll 빈도가 높아져 네트워크 / 라운드트립 오버헤드, 너무 크면 (예: 500) 한 번에 받은 메시지 처리 시간이 `max.poll.interval.ms` 를 초과해 rebalance 위험.

#### `consumer.enable-auto-commit = false`

- 자동 commit 비활성화. **메시지 처리 완료 (트랜잭션 commit) 직후 명시적 ack**.
- 처리 도중 인스턴스 죽으면 ack 안 된 메시지는 재처리 → at-least-once 보장.
- 자동 commit 이면 처리 전에 offset 이 진행돼 **메시지 유실 위험** — 본 도메인의 정합성 요구와 정면 충돌.

### 3.3 조합 효과

```
초당 처리 상한 ≈ concurrency × (1 / 평균 처리 시간) × 안전 계수
              = 3 × (1 / 0.1s) × 0.7
              ≈ 21 req/s/instance (보수적 상한)
```

> 실제 측정값은 [인프라 사이징 보고서](infra-sizing.md) 참조. 본 보고서는 throttle 설계 근거에 집중.

---

## 4. Kafka 가 자연 buffer 인 이유

| 측면 | 설명 |
|---|---|
| **Persistent log** | producer 가 쓴 메시지는 broker 의 commit log 에 영속. consumer lag 이 쌓여도 데이터 유실 없음 |
| **Pull 기반** | consumer 가 처리 가능한 양만 가져옴 → 자연스러운 backpressure |
| **Partition 기반 병렬화** | partition 수 ↔ consumer concurrency 로 throughput 조정 가능 |
| **lag 가시화** | Kafka UI / Prometheus 로 큐 깊이 모니터링 — 이상 감지 용이 |

**결과** — Server B 는 burst 트래픽을 그대로 publish 해도 OK. 초과분은 Kafka partition 에 쌓이고, C 가 자기 속도대로 소비. 사용자 입장에서는 "200 ACCEPTED" 만 받고 결과 폴링으로 확인.

---

## 5. 결정 요약

| 위험 | 대응 | 메커니즘 |
|---|---|---|
| C 의 비관적 락 큐 폭주 | 한 번에 처리할 메시지 상한 | `max-poll-records=50` |
| 1 vCPU 컨텍스트 스위칭 폭주 | listener 스레드 수 제한 | `concurrency=3` |
| 처리 도중 인스턴스 죽으면 메시지 유실 | 명시적 ack | `enable-auto-commit=false` + `ack-mode=RECORD` |
| 사용자 응답 경로의 latency 악화 | 진입점에 throttle 두지 않음 | A 의 Bucket4j 미적용 (ADR-005) |
| Burst 트래픽 흡수 | Kafka 가 buffer | partition 에 쌓임, lag 으로만 보임 |

[← README](../../README.md)
