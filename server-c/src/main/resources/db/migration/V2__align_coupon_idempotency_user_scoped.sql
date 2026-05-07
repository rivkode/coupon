-- V1 의 idempotency_key 단독 UNIQUE 를 (user_id, idempotency_key) 복합 UNIQUE 로 정합화.
-- 근거: ADR-004 의 user 별 격리 의도 — 다른 user 가 우연히 같은 idem 을 써도 서로 다른 발급으로 처리.
-- server-b 의 Outbox UNIQUE (user_id, idempotency_key) 와 정합 (PR #12 결정).
-- code UNIQUE 는 V1 그대로 유지 — Redis 가 발급한 코드의 영구 권위.
ALTER TABLE coupon DROP INDEX uk_coupon_idempotency_key;
ALTER TABLE coupon ADD CONSTRAINT uk_coupon_user_idem UNIQUE (user_id, idempotency_key);
