-- 발급 요청 audit / 멱등성 1차 캐시.
-- (user_id, idempotency_key) UNIQUE 가 멱등성의 1차 보장 (Redis 캐시 hit miss 시 fallback).
-- Server C 의 coupon.idempotency_key UNIQUE 가 최종 보장 (CLAUDE.md ADR-004).
CREATE TABLE issue_request (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    request_id      VARCHAR(36)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    event_id        BIGINT       NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    coupon_code     VARCHAR(12)  NULL,
    failure_reason  VARCHAR(255) NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_issue_request_user_idem  UNIQUE (user_id, idempotency_key),
    CONSTRAINT uk_issue_request_request_id UNIQUE (request_id),
    KEY idx_issue_request_event_created  (event_id, created_at),
    KEY idx_issue_request_status_created (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
