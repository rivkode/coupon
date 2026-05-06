-- Server B 의 Outbox 테이블 (CLAUDE.md ADR-002).
-- B 가 Redis 에서 atomic 재고 차감 + 쿠폰 코드 발급에 성공하면, 같은 트랜잭션에서 본 테이블에 INSERT.
-- 별도 poller 가 published=false 행을 읽어 Kafka 로 발행 후 published=true 로 마킹 (PR #10 / Day 3).
--
-- coupon_code UNIQUE: B 의 atomic 발급 결과 코드의 권위 (Redis 가 발급한 코드의 RDBMS 미러).
-- idempotency_key UNIQUE: 같은 키로 두 번 INSERT 시 DB 가 거부 — Outbox 중복 적재 방지 (CLAUDE.md ADR-004).
-- payload JSON: Kafka 로 발행할 본문 (eventId, userId, issuedAt 등). 컬럼 분리하지 않은 이유는
--   본 테이블이 검색 대상이 아니고 (poller 는 published flag 만 본다) payload 는 그대로 전파되기 때문.
-- idx_outbox_unpublished: poller 가 미발행 행만 효율적으로 스캔 (보통 거의 비어 있음).
CREATE TABLE coupon_issue_outbox (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    coupon_code     VARCHAR(12)  NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    payload         JSON         NOT NULL,
    published       BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at    DATETIME(3)  NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_outbox_coupon_code     UNIQUE (coupon_code),
    CONSTRAINT uk_outbox_idempotency_key UNIQUE (idempotency_key),
    KEY idx_outbox_unpublished (published, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
