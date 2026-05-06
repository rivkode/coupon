-- Outbox 의 idempotency UNIQUE 를 (user_id, idempotency_key) 로 user-scoped 변경.
-- ADR-004 (server-a `(user_id, idempotency_key) UNIQUE` + server-b user-scoped 1차 캐시) 정합.
--
-- 변경:
--   1. user_id 컬럼 추가 (NOT NULL). 기존 행이 없는 Day 2 단계라 backfill 불필요.
--   2. uk_outbox_idempotency_key (idem-only) 제거 → uk_outbox_user_idem (user_id, idempotency_key) 추가.
--   3. coupon_code UNIQUE 는 그대로 유지 (Redis 가 발급한 코드의 권위, user 무관).

ALTER TABLE coupon_issue_outbox
    ADD COLUMN user_id BIGINT NOT NULL AFTER coupon_code;

ALTER TABLE coupon_issue_outbox
    DROP INDEX uk_outbox_idempotency_key,
    ADD CONSTRAINT uk_outbox_user_idem UNIQUE (user_id, idempotency_key);
