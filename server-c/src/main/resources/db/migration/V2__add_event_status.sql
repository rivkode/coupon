-- 이벤트 lifecycle 상태 컬럼 추가.
-- EventCacheRefresher 가 status=IN_PROGRESS 인 이벤트만 백그라운드 갱신 대상으로 삼아
-- 캐시 stampede 를 방지 (CLAUDE.md 평가항목 ③).
ALTER TABLE event ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CREATED';
CREATE INDEX idx_event_status ON event (status);
