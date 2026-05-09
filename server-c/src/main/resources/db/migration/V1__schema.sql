-- Server C 의 영구 저장소 — 이벤트 / 쿠폰 종류 / 재고 / 사용자 쿠폰 / Outbox.
-- 재고는 coupon_type_inventory 의 row 를 비관적 락(SELECT ... FOR UPDATE) 으로 차감 (CLAUDE.md ADR-003).
-- 멱등성은 user_coupon 의 (user_id, coupon_type_id) UNIQUE 가 보장 (ADR-004).
-- code UNIQUE: 발급된 쿠폰 코드의 권위. version: redeem 낙관적 락(ADR-007).

CREATE TABLE event (
    event_id        BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(200) NOT NULL,
    content         TEXT         NULL,
    started_at      DATETIME(3)  NOT NULL,
    ended_at        DATETIME(3)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE coupon_type (
    coupon_type_id  BIGINT       NOT NULL AUTO_INCREMENT,
    event_id        BIGINT       NOT NULL,
    name            VARCHAR(200) NOT NULL,
    discount_rate   INT          NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (coupon_type_id),
    KEY idx_coupon_type_event (event_id),
    CONSTRAINT fk_coupon_type_event FOREIGN KEY (event_id) REFERENCES event (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 재고 row — Kafka consumer 가 SELECT ... FOR UPDATE 로 잠그고 차감.
-- coupon_type 당 1 row 가정. (event_id, coupon_type_id) UNIQUE 로 정합 보장.
CREATE TABLE coupon_type_inventory (
    coupon_type_inventory_id BIGINT NOT NULL AUTO_INCREMENT,
    event_id                 BIGINT NOT NULL,
    coupon_type_id           BIGINT NOT NULL,
    total_inventory          INT    NOT NULL,
    available_count          INT    NOT NULL,
    created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (coupon_type_inventory_id),
    CONSTRAINT uk_inventory_event_type UNIQUE (event_id, coupon_type_id),
    CONSTRAINT fk_inventory_coupon_type FOREIGN KEY (coupon_type_id) REFERENCES coupon_type (coupon_type_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 발급 결과 — UNIQUE (user_id, coupon_type_id) 가 1 인 1 장 + Kafka 멱등성을 동시에 보장.
-- code 는 발급된 쿠폰의 외부 식별자 (redeem 시 사용).
CREATE TABLE user_coupon (
    user_coupon_id  BIGINT       NOT NULL AUTO_INCREMENT,
    code            VARCHAR(32)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    event_id        BIGINT       NOT NULL,
    coupon_type_id  BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    issued_at       DATETIME(3)  NOT NULL,
    used_at         DATETIME(3)  NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_coupon_id),
    CONSTRAINT uk_user_coupon_code UNIQUE (code),
    CONSTRAINT uk_user_coupon_user_type UNIQUE (user_id, coupon_type_id),
    KEY idx_user_coupon_user (user_id),
    CONSTRAINT fk_user_coupon_coupon_type FOREIGN KEY (coupon_type_id) REFERENCES coupon_type (coupon_type_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Outbox — Kafka publish 의 트랜잭션 안전성 (ADR-002).
-- 발급 트랜잭션 안에서 INSERT, OutboxPoller 가 trans 밖에서 publish + status 갱신.
CREATE TABLE outbox_event (
    outbox_event_id BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at    DATETIME(3)  NULL,
    PRIMARY KEY (outbox_event_id),
    KEY idx_outbox_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
