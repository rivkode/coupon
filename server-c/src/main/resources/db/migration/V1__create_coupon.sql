-- Server C 의 쿠폰 영구 저장 (Source of Truth).
-- code UNIQUE: 발급된 쿠폰 코드의 권위.
-- idempotency_key UNIQUE: 멱등성 최종 보장 (CLAUDE.md ADR-004) — Kafka 메시지 재처리 시 DB 가 중복 거부.
-- version: @Version 낙관적 락 (CLAUDE.md ADR-007) — redeem 동시 호출 시 한 명만 성공.
CREATE TABLE coupon (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    code            VARCHAR(12)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    event_id        BIGINT       NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    issued_at       DATETIME(3)  NOT NULL,
    used_at         DATETIME(3)  NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_coupon_code            UNIQUE (code),
    CONSTRAINT uk_coupon_idempotency_key UNIQUE (idempotency_key),
    KEY idx_coupon_user_event (user_id, event_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
