package com.promotion.common.coupon;

import java.time.Instant;
import java.util.Objects;

/**
 * Server C → Server B 의 Kafka 메시지 (`coupon-issue-result` 토픽). 발급 처리 결과 이벤트.
 * key = userId. couponCode 는 status == SUCCESS 일 때만 non-null.
 */
public record CouponIssueResultPayload(
        String requestId,
        long userId,
        long eventId,
        long couponTypeId,
        CouponIssueResultStatus status,
        String couponCode,
        Instant processedAt
) {
    public CouponIssueResultPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        if (status == CouponIssueResultStatus.SUCCESS) {
            Objects.requireNonNull(couponCode, "couponCode (status=SUCCESS)");
        }
        Objects.requireNonNull(processedAt, "processedAt");
    }
}
