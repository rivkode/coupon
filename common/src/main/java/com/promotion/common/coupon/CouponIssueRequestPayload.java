package com.promotion.common.coupon;

import java.time.Instant;
import java.util.Objects;

/**
 * Server B → Server C 의 Kafka 메시지 (`coupon-issue-request` 토픽). 발급 신청 이벤트.
 * key = userId (같은 user 의 이벤트는 partition 순서 보장).
 */
public record CouponIssueRequestPayload(
        String requestId,
        long userId,
        long eventId,
        long couponTypeId,
        Instant requestedAt
) {
    public CouponIssueRequestPayload {
        Objects.requireNonNull(requestId, "requestId");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive: " + userId);
        }
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive: " + eventId);
        }
        if (couponTypeId <= 0) {
            throw new IllegalArgumentException("couponTypeId must be positive: " + couponTypeId);
        }
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
