-- 서비스별 schema 생성 (CLAUDE.md ADR-006).
-- 신규 설계: server-a (audit), server-c (영구 저장 + 비관적 락 inventory + outbox).
-- server-b 는 Redis only — MySQL schema 없음.
CREATE DATABASE IF NOT EXISTS server_a
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS server_c
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON server_a.* TO 'promotion'@'%';
GRANT ALL PRIVILEGES ON server_c.* TO 'promotion'@'%';
FLUSH PRIVILEGES;
