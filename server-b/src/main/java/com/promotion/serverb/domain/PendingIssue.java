package com.promotion.serverb.domain;

import java.time.Instant;

/** Redis hash 의 의미적 표현. PendingIssueScheduler 가 사용. */
public record PendingIssue(
        String requestId,
        long userId,
        long eventId,
        long couponTypeId,
        IssuePendingStatus status,
        Instant createdAt
) {
}
