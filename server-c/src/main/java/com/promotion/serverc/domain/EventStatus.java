package com.promotion.serverc.domain;

/**
 * 이벤트 lifecycle 상태.
 *
 * <p>전이: CREATED → IN_PROGRESS → ENDED. CANCELLED 는 어느 상태에서나 진입 가능.
 *
 * <p>EventCacheRefresher 는 IN_PROGRESS 상태의 이벤트만 백그라운드 갱신 대상으로 삼는다 —
 * 생성/종료/취소 상태는 빈번 조회 트래픽이 없으므로 stampede 위험이 작다.
 */
public enum EventStatus {
    CREATED,
    IN_PROGRESS,
    ENDED,
    CANCELLED
}
