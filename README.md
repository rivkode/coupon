# 쿠폰 발급 시스템

이벤트별 선착순 할인 쿠폰 발급 및 사용 시스템.

Java21, SpringBoot 3.5.14, MySQL, Redis, Kafka 를 사용한 멀티 모듈 프로젝트입니다.

---

# 빠른 실행

```shell
docker compose up -d --build
./gradlew test
```

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
- [유량 조절](docs/reports/rate-limiting.md)
  - Kafka consumer 의 `max.poll.records` / `concurrency` 로 1 vCPU MySQL-C 가 처리 가능한 만큼만 메시지를 흘려보낸다.
- [인프라 사이징](docs/reports/infra-sizing.md)
  - k6 로 단일 인스턴스 TPS 를 측정하고 Little's Law (L = λW) 로 목표 부하에 필요한 인스턴스 수를 산정한다.

---

# 성능 테스트 환경

k6 기반 부하 시나리오. 사전에 `docker compose up -d --build` 로 인프라 + 3 서비스 부팅 후 실행.

```bash
# 1000 TPS 시나리오 (기본)
./load-test/run-integrated.sh

# 500 TPS 시나리오
./load-test/run-5k-integrated.sh

# 시나리오 직접 지정
SCENARIO=load-test/scenarios/smoke.js           ./load-test/run-integrated.sh
SCENARIO=load-test/scenarios/issue-500-tps.js   ./load-test/run-integrated.sh
SCENARIO=load-test/scenarios/issue-1k-tps.js    ./load-test/run-integrated.sh
SCENARIO=load-test/scenarios/event-cache-ttl.js ./load-test/run-integrated.sh

# 옵션 — 재고 / 이벤트 / 쿠폰 종류 변경
TOTAL_INVENTORY=1000000 ./load-test/run-integrated.sh    # 매진 없이 TPS 한계만 측정
EVENT_ID=1 COUPON_TYPE_ID=1 ./load-test/run-integrated.sh
```

| 시나리오 | 용도 |
|---|---|
| `smoke.js` | 헬스체크 수준의 최소 부하 (배포 직후 sanity) |
| `issue-500-tps.js` | 500 TPS 발급 부하 |
| `issue-1k-tps.js` | 1,000 TPS 발급 부하 (인스턴스당 목표 TPS) |
| `event-cache-ttl.js` | 이벤트 조회 + 캐시 stampede 검증 (평가 항목 ③) |

> 상세 절차 / 결과 해석은 [`load-test/README.md`](load-test/README.md) 참조.
