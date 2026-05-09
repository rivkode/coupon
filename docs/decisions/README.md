# ADR 인덱스

ADR 본문은 [`CLAUDE.md` §6](../../CLAUDE.md) 에 단일 출처로 유지합니다. 본 문서는 외부에서 ADR 을 빠르게 찾기 위한 인덱스입니다.

| ADR | 제목 | 핵심 |
|---|---|---|
| [001](../../CLAUDE.md#adr-001-a→b-동기-호출-단-즉시-접수-완료-응답) | A→B 동기 호출, 즉시 "접수 완료" 응답 | 응답 latency 와 발급 처리(C 비관적 락) 분리 |
| [002](../../CLAUDE.md#adr-002-saga-choreography--outbox-패턴-c-에-위치) | Saga(choreography) + Outbox(C) | orchestrator 부담 회피, 결과는 Outbox + Kafka |
| [003](../../CLAUDE.md#adr-003-재고-관리는-server-c-의-mysql--비관적-락) | 재고 = C MySQL `coupon_type_inventory` + `SELECT FOR UPDATE` | 발급이 비동기 처리이므로 락 경합과 응답 분리 |
| [004](../../CLAUDE.md#adr-004-멱등성은-user_id-coupon_type_id-unique-로-보장) | 멱등성 = `(user_id, coupon_type_id)` UNIQUE | 1 인 1 장 제약 자체가 멱등성 |
| [005](../../CLAUDE.md#adr-005-a-의-사용자별-rate-limit-제거) | A 의 Rate Limit 제거 | 1 인 1 장 UNIQUE 가 자연 차단 |
| [006](../../CLAUDE.md#adr-006-데이터베이스는-서비스별-분리-database-per-service) | Database per Service (A=MySQL, B=Redis, C=MySQL) | 서비스 독립 배포 |
| [007](../../CLAUDE.md#adr-007-쿠폰-사용redeem-은-낙관적-락) | Redeem 낙관적 락 (`@Version`) | 동시 redeem 은 드묾, 비관적 락은 오버헤드 |
| [008](../../CLAUDE.md#adr-008-b-의-kafka-publish-실패는-producer-재시도--스케줄러-보완) | B Kafka publish 실패 = producer 재시도 + `@Scheduled` 10 초 보완 | UNIQUE 가 중복 publish 방어 |
| [009](../../CLAUDE.md#adr-009-b↔c-양방향-kafka-issue-토픽--result-토픽) | B↔C 양방향 Kafka | 사용자 폴링이 B Redis 만 보면 되도록 |
| [010](../../CLAUDE.md#adr-010-a-의-요청-로그는-per-request-commit-batch-insert-아님) | A 요청 로그 per-request commit | 응답이 즉시 "접수 완료" 라 batch 불필요 |
