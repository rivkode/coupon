# 쿠폰 발급 시스템

본 프로젝트는 Claude Code Opus 4.7 을 사용해서 구현했으며 기술스택은 Java 21, SpringBoot 3.5.14, MySQL, Redis, Kafka 를 사용한 멀티 모듈 프로젝트입니다.

---

# 문서

## 쿠폰 서비스 설계

- [요구사항 분석](docs/design/requirements.md)
- [시스템 아키텍처 (data-flow)](docs/design/architecture.md)
- [다이어그램](docs/design/diagrams.md)
  - [시퀀스 다이어그램](docs/design/diagram-sequence.md)
  - [상태 다이어그램](docs/design/diagram-state.md)
- [ERD](docs/design/erd.md)
- [API 명세](docs/design/api-spec.md)

## 기술 보고서

- [대량 트래픽](docs/reports/traffic.md)
  - Server A 는 즉시 "접수 완료" 만 응답하고 실제 발급은 Kafka 로 비동기 처리 — 재고 row 비관적 락 경합을 사용자 응답 경로에서 분리해, 사용자는 커넥션 풀 점유 / 락 대기 없이 짧은 latency 를 받는다.
- [동시성 이슈 분석 및 해결](docs/reports/concurrency.md)
  - 비관적 락을 사용해서 쿠폰 재고관리를 한다.
- [분산 서비스 간 정합성](docs/reports/distributed-consistency.md)
  - Redis 에 신청이 적재된 시점부터 **at-least-once** 가 보장 (Kafka 유실 시 B 스케줄러가 10 초 이상 pending 을 C 에 직접 조회해 재동기화)
  - C 는 발급 결과를 Outbox 로 Kafka 에 안전 발행한다.
  - `(user_id, coupon_type_id)` UNIQUE 로 1 인 1 장 멱등 보장
- [캐시](docs/reports/cache.md)
  - 백그라운드 스케줄러가 Redis 캐시를 주기적으로 미리 갱신해 TTL 만료 자체를 회피, 캐시 stampede 를 차단한다.
- [유량 제어](docs/reports/rate-limiting.md)
  - Kafka consumer 의 `max.poll.records` / `concurrency` 로 MySQL-C 가 처리 가능한 만큼만 메시지를 흘려보낸다.
- [인프라 사이징](docs/reports/infra-sizing.md)
  - k6 로 단일 인스턴스 TPS 를 측정하고 인프라 사이징을 구한다.
  - 인스턴스 1 대의 CPU 상한은 약 980 req/s, 운영 헤드룸(가용률 70 %) 을 반영해 2 대로 산정한다.
- [병목 분석 (1,000 TPS 재측정)](docs/reports/load-test-bottleneck-analysis.md)
  - 한 번에 한 변수만 바꾸는 방식으로 재시도 증폭 / 스케줄러 되먹임 / Kafka / MySQL 가설을 차례로 반증하고, 원인을 server-a 의 CPU 포화와 커넥션 풀 과다 설정으로 특정한다.

## 부하 검증 결과 (2026-08-27)

목표 부하는 사용자 1,000 명이 10 초 동안 1 인당 10 건을 요청하는 **1,000 TPS** 다. 현재 코드가 이 부하를 견디는지 재측정했다.

| 항목 | 값 |
|---|---|
| steady-state 처리량 | 977 ~ 988 req/s |
| 실패율 | 0 % |
| p95 | 37 ~ 40 ms |
| server-a CPU | 0.95 ~ 1.00 (1 코어 포화) |

조건은 기본 설정(`HIKARI_POOL_SIZE=10`) 이고, 회차별 원본 산출물은 `load-test/experiments/results/`, 측정 과정 기록은 `load-test/experiments/RUNLOG.md` 에 있다.

읽는 데 필요한 단서 세 가지.

- 처리량이 1,000 에 못 미치는 이유는 장애가 아니라 이용률이 1 에 붙은 큐잉이다. server-a 가 CPU 를 다 쓰는 지점이 곧 상한이므로, 같은 설정에서도 회차에 따라 p95 가 37 ms 와 800 ms 사이를 오간다.
- 그래서 977 을 운영 기준으로 그대로 쓰지 않고, 가용률 70 % 를 적용해 인스턴스 2 대로 산정한다 (`1,000 / (977 × 0.7)` ≈ 1.46).
- 커넥션 풀을 50 으로 키우면 오히려 붕괴한다. pool=10 은 latency 튜닝값이 아니라 1 vCPU 를 보호하는 유입 제한이다.

---

## 과제 관련 스터디

- [학습 노트](docs/study.md) — 본 과제를 수행하며 처음 학습한 내용에 대해 정리한다.

---

# 성능 테스트 환경


## 빠른 실행

k6 기반 부하 시나리오. 사전에 `docker compose up -d --build` 로 인프라 + 3 서비스 부팅 후 실행.

```shell
docker compose up -d --build
./gradlew test #Java 21
./load-test/run-integrated.sh              # 1,000 TPS 통합 시나리오
./load-test/run-500tps-10sec-10times.sh    # 500 TPS x 10 초를 10 회 반복

# 의존성 설치
brew install k6
brew install jq

# 의존성 설치 확인                                                                                                                                                                                                                                
docker --version
curl --version                                                                                                                                                                                                                        
k6 version                                                                
jq --version                        
bash --version
``` 

> run-integrated.sh — 1,000 TPS 시나리오(`load-test/scenarios/issue-1k-tps.js`) 를 실행하고, A 가 접수한 건수와 server-c 에 실제로 발급된 쿠폰 수를 나누어 집계합니다. `SCENARIO` / `TOTAL_INVENTORY` 환경변수로 시나리오와 재고 규모를 바꿀 수 있습니다.

> run-500tps-10sec-10times.sh — 500 TPS × 10초 부하 시나리오를 10회 반복 실행하여 회차별 지표를 요약합니다. 초기 회차(1~4회)는 JIT 컴파일러 최적화 및 시스템 워밍업을 위한 Cold Start 구간으로 간주하며, 테스트 결과 성능은 보통 5회차 부터 수렴하는 것을 확인했습니다. 각 회차 사이에는 시스템 안정을 위해 1초의 대기 시간(sleep)을 둡니다.

> 상세 절차 / 결과 해석은 [`load-test/README.md`](load-test/README.md) 참조.
