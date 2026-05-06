-- 프로모션 이벤트 마스터.
-- 본 테이블의 total_stock 은 운영 입력 시점의 스냅샷.
-- 실시간 재고 권위는 Server B 의 Redis 샤드 합계 (CLAUDE.md ADR-003).
CREATE TABLE event (
    id          BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    total_stock INT          NOT NULL,
    started_at  DATETIME(3)  NOT NULL,
    ended_at    DATETIME(3)  NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT chk_event_period CHECK (started_at < ended_at),
    CONSTRAINT chk_event_stock  CHECK (total_stock > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Day 1 기본 이벤트 (kickoff Phase 9 시나리오용).
INSERT INTO event (id, name, total_stock, started_at, ended_at)
VALUES (1, 'Concert Pre-Sale 2026', 10000, '2026-01-01 00:00:00.000', '2027-01-01 00:00:00.000');
