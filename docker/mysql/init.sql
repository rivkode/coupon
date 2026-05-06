-- 서비스별 schema 생성 (Database per Service 원칙 — CLAUDE.md ADR-006)
-- 로컬 환경에서는 단일 MySQL 인스턴스에 두 schema 로 분리 운영.
CREATE DATABASE IF NOT EXISTS server_a
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS server_c
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON server_a.* TO 'promotion'@'%';
GRANT ALL PRIVILEGES ON server_c.* TO 'promotion'@'%';
FLUSH PRIVILEGES;
