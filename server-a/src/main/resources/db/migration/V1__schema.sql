-- Server A — 발급 요청 로그 (per-request commit, ADR-010).
-- (user_id, coupon_type_id) UNIQUE 는 Server C 가 권위 (ADR-004) — 본 테이블은 audit/추적용.
CREATE TABLE issue_request (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    request_id      VARCHAR(36)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    event_id        BIGINT       NOT NULL,
    coupon_type_id  BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_issue_request_user (user_id),
    KEY idx_issue_request_request_id (request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
