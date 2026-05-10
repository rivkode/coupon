package com.promotion.serverb.domain;

import java.time.Instant;

/**
 * Redis hash 의 의미적 표현. PendingIssueScheduler 가 사용.
 *
 * <p>publishAttempts 는 ADR-008 의 재발행 cap 추적용. 최초 accept 시 1, 스케줄러가 재발행할 때마다 증가.
 */
public record PendingIssue(
        String requestId,
        long userId,
        long eventId,
        long couponTypeId,
        IssuePendingStatus status,
        Instant createdAt,
        int publishAttempts
) {
}
