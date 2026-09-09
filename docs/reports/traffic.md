# 대량 트래픽

>
> **자원 제약 표기 정정** — `docker-compose.yml` 의 `cpus: 1.0` 제한은 앱 컨테이너(server-a/b/c)에만,
> `cpus: 2.0` 은 Kafka 에만 걸려 있다. **MySQL 과 Redis 에는 CPU 제한이 없다.** 아래 서술의 "MySQL" 은
> 1 vCPU 로 제한된 컨테이너가 아니라 호스트 자원을 공유하는 컨테이너다.

![system-design-traffic](../photo/system-design-traffic.png)

## 0. 요약

- **핵심 결정**: 사용자 응답 경로에서 **락 경합을 분리**. 트래픽 수용은 Redis (HSETNX) + Kafka publish 로 비동기화, 비관적 락 + 재고 차감은 별도 워커 (Server C) 가 처리.
- **at-least-once 경계**: **Redis 에 신청이 적재된 시점** 부터 보장. 이후 Kafka publish 실패 / 메시지 유실은 스케줄러가 회복.
- **거부한 대안**: ① 단일 동기 처리 (사용자가 락 경합을 직접 대기) ② Batch INSERT (큐 / 플러시 정책의 운영 복잡도)
- **검증** (2026-08-27 재측정): 단일 인스턴스가 **1,000 TPS × 60 초 부하를 실패 0 % 로 소화** — 안정 구간
  **977 건/s, p95 40 ms**. HikariCP 풀은 **10 유지** (키우면 오히려 붕괴). 이전 판의 "단일 인스턴스 500 TPS" 는
  500 TPS 를 제시한 부하에서 나온 값이라 상한이 아니었고, 측정 코드에 `@Transactional` 이 외부 호출을 감싸고 있었다
  — [병목 분석 보고서](load-test-bottleneck-analysis.md).

---

## 1. 문제 정의

- 목표: 시스템 전체 평균 **1,000 TPS** (1,000 명 × 10 건 / 10 초) · 인스턴스는 1 vCPU / 2 GB RAM (CLAUDE.md §2)
- 도메인: 선착순 쿠폰 발급은 본질적으로 **재고 row 1 개에 락 경합이 집중되는 hot-row 워크로드**
- 위험: 락 경합 트래픽을 **사용자 응답 경로에서 직접 받으면**
  - 락 대기로 Tomcat 워커 스레드 / DB 커넥션이 장시간 점유됨 → 후속 요청이 줄줄이 풀 고갈 대기
  - 1 vCPU 환경에서 락 큐가 폭발하면 카스케이딩 latency / 실패율 급증

가장 먼저 결정해야 했던 것은 **"커넥션을 오래 잡는 락 경합 트래픽을 사용자 응답 경로에 직접 받게 하면 안 된다"** 라는 원칙.

---

## 2. 고려한 대안

### 대안 A — 단일 동기 처리 (User → A → C 동기, 락 경합을 직접 노출)

가장 단순한 구조. 사용자가 즉시 발급 결과 (성공 / 매진) 를 받는다.

- ❌ **거부 이유**: MySQL 에서 비관적 락의 직렬화 처리량이 사용자 응답 throughput 상한을 결정. 락 대기 중 DB 커넥션 / Tomcat 워커가 점유돼 p95 / p99 latency 가 비선형으로 악화.

### 대안 B — Batch INSERT (요청 / 발급 모두 batch)

요청을 메모리 큐에 모아 일정 단위로 일괄 INSERT.

- ❌ **거부 이유**:
  - 큐 / 플러시 정책 / 인스턴스 종료 시 손실 / 백프레셔 등 **운영 복잡도가 급증**.
  - **단건 INSERT (per-request commit) 측정 결과 1 vCPU 에서도 무리 없음** — 단순성 우선.
- 결과적으로 ADR-010 (A 의 요청 로그는 per-request commit) 으로 정착.

### 대안 C — 요청 처리 ↔ 동시성 제어 분리 (채택)

사용자 응답 경로 = "**접수 완료**" 까지만 보장. 락 경합은 비동기 워커가 별도 처리.

- ✅ **채택 이유**: 도메인 특성상 사용자는 발급 결과를 즉시 알 필요 없음. 사용자가 알고 싶은 것은 "**내가 결국 받았는가**" 이므로 10 초 뒤 보유 목록에서 확인되어도 무방.

---

## 3. 결정 — 요청 처리와 동시성 제어 분리

### 3.1 도메인 정당성

선착순 쿠폰 도메인의 **결과 일관성 요구 수준이 낮음**:
- 사용자 관점에서 "즉시 결과" vs "10 초 뒤 결과" 의 UX 차이가 미미.
- "접수 완료 → 폴링 / 내 쿠폰 조회" 흐름이 자연스럽고 일반적.

### 3.2 두 경로 분리

**사용자 응답 경로** — 빠르고, 락이 없음

```
User → Server A → Server B → Redis 적재 → Kafka publish → 200 ACCEPTED (ms 단위)
```

- A: 요청을 받아 `issue_request` audit (per-request commit) 만 남기고 B 로 위임
- B: Redis `HSETNX issue:pending:{userId}:{couponTypeId}` 로 중복 차단 + pending 적재
- Kafka 로 `coupon-issue-request` publish 후 즉시 200 응답
- **이 경로에 비관적 락 없음** — 사용자는 Tomcat 워커 / DB 커넥션을 짧게만 점유

**비동기 처리 경로** — 락 경합 격리

```
Kafka → Server C consumer → MySQL-C (SELECT FOR UPDATE + INSERT) (건당 6.8 ms, 스레드 1 개 기준 141 건/s)
```

- Consumer throttle (실효값 `max.poll.records=50`, listener 스레드 1 개) 로 **MySQL-C 가 받아낼 양만** 흘림
  ([유량 제어 보고서 §0](rate-limiting.md) — yml 기본값과 compose override 가 다르다)
- 락 대기는 워커 풀 안에서만 발생, **사용자 응답 경로에 영향 없음**

---

## 4. at-least-once 경계 — Redis 적재 시점

### 4.1 왜 Redis 인가

at-least-once 보장을 위해서는 "**받았다**" 를 영속 가능한 매체에 기록해야 한다. RDB 는 1 vCPU 환경에서 commit 비용이 사용자 응답 latency 를 직접 늘리므로 부적합.

Redis 채택 이유:
- 쓰기 / 읽기 latency 가 RDB 대비 빠름 (마이크로초 단위).
- 사용자에게는 빠르게 200 응답을 돌려주면서, Kafka publish 는 별도 시도 가능.
- publish 실패해도 스케줄러가 ZSET 인덱스를 보고 **빠르게 재발행** 가능.

### 4.2 보장 흐름

```
User → A → B → [Redis HSETNX + ZADD]   ← ⭐ 이 시점부터 at-least-once 보장
              → Kafka publish 시도
                  ↓ (실패해도 OK)
              → 200 ACCEPTED 즉시 응답

B 의 @Scheduled (1s 주기):
  ZRANGEBYSCORE 로 10s 이상 PENDING 인 신청 발견
  → C 의 internal GET 으로 실제 처리 여부 확인
  → 미처리면 Kafka 재발행 (publishAttempts ≤ 3, 30s SLA)
```

- Kafka publish 가 실패하더라도 Redis 에 신청이 남아 있으므로 스케줄러가 회복.
- **사용자가 한 번 200 받은 신청은 영구 처리** — at-least-once 의 의미.
- 중복 publish 의 안전성은 Server C 의 `(user_id, coupon_type_id)` UNIQUE 가 보장 (멱등 처리는 [정합성 보고서](distributed-consistency.md)).

---

## 5. 커넥션 풀 튜닝 — 그리고 그것이 문제가 아니었다는 것

HikariCP `maximumPoolSize` 를 단계적으로 조정한 이력이다.

| 단계 | 풀 사이즈 | 당시 관찰 | 지금의 해석 |
|---|---|---|---|
| 1 | 10 | 부하 시 풀 고갈 (`waiting=489`) → 사용자 latency 급증 | 풀이 좁아서가 아니라 **커넥션을 쥔 쪽이 느려서** 고갈됐다 |
| 2 | 50 | 처리량이 오히려 줄고 실패율 상승 | 풀이 제약이었다면 나올 수 없는 결과. 여기서 가설을 버렸어야 했다 |
| 3 | 20 | 1,000 TPS 3 차 측정에서 ACCEPTED 최다 | 당시의 잠정 결론 |
| 4 | **10 (현재 적용값)** | 1,000 TPS 재측정에서 **503 0 건 / 실패 0 % / 977~988 건/s** | 풀은 **유입 제한**으로 작동한다 |

> **최종 적용값은 10 이다.** 출처는 `server-a/src/main/resources/application.yml` 의
> `spring.datasource.hikari.maximum-pool-size: ${HIKARI_POOL_SIZE:10}` — 이 문서가 아니라 그 파일이 진실이다.

### 풀 고갈에는 원인이 둘이고 증상이 같다

| | 원인 | 풀을 키우면 |
|---|---|---|
| (a) 진짜 부족 | 뒤의 DB 는 더 받을 수 있는데 앞의 풀이 좁아 못 넘긴다 | 처리량이 는다 |
| (b) 보유 시간이 길다 | 커넥션을 쥔 스레드가 일을 못 끝낸다 (풀 대기는 **증상**) | 더 나빠진다 |

두 경우를 가르는 지표는 **커넥션 보유 시간**이다. 측정값은 (b) 였다.

| 상태 | 보유 시간 mean | 획득 대기 mean | 풀 이론 용량 (`pool 10 ÷ 보유시간`) | 실측 처리량 |
|---|---|---|---|---|
| `@Transactional` 이 B 호출을 감쌈 | 14.6 ms | **727 ms** | 685 /s | 661 /s |
| 트랜잭션 분리 (현재) | **5.0 ms** | **0.4 ms** | **2,000 /s** | **977 /s** |

**같은 코드에서 트랜잭션 경계만 되돌려 잰 값이다.** 외부 HTTP 왕복이 트랜잭션 안에 있으면 커넥션을 그 시간만큼
쥐고 있게 되고, 풀 이론 용량이 CPU 천장(≈980/s) 아래로 내려가 **풀이 병목이 된다.** 분리하면 용량이 천장 위로
올라가 병목이 CPU 로 옮겨간다. 병목이 사라진 것이 아니라 **옮겨간** 것이다.

**교훈** — 풀 크기를 만지기 전에 **보유 시간부터 본다.** `waiting=N` 은 "커넥션이 부족하다" 가 아니라
"커넥션을 쥔 쪽이 느리다" 일 수 있고, 두 경우의 처방은 정반대다. 1 vCPU 환경에서 pool=10 은 latency 튜닝값이
아니라 **CPU 를 보호하는 유입 제한**이다 — 초과 요청은 CPU 를 쓰지 않고 풀 큐에서 대기한다.

---

## 6. 검증 결과

1,000 TPS × 60 초, 예열 1 회를 버리고 3 회 측정 (`issue-1k-tps.js`).

| 회차 | 접수 성공 | 실패율 | p95 |
|---|---|---|---|
| 1차 (예열 직후) | 53,074 | 0 % | 782 ms |
| 2차 | 58,628 | **0 %** | **40 ms** |
| 3차 | 59,248 | **0 %** | **37 ms** |

- 세 회차 모두 실패가 없고, 안정 구간은 시나리오 임계값 `p95 < 200 ms` 를 통과한다.
- 명세 조건 (재고 100 장 / 1,000 TPS × 10 초) 의 매진 시나리오에서는 **SUCCESS 정확히 100 건, 재고 잔여 0,
  FAILED 0** — 재고 정합성이 깨지지 않는다.

- 락 경합 워커 (Server C) 의 처리량 / consumer throttle 상세 → [동시성 보고서](concurrency.md), [유량 제어 보고서](rate-limiting.md)

[← README](../../README.md)
