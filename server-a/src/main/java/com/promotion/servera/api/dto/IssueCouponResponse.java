package com.promotion.servera.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.promotion.servera.domain.IssueRequest;
import com.promotion.servera.domain.IssueRequestStatus;

/**
 * 쿠폰 발급 응답.
 *
 * <p>{@code couponCode} 는 status == SUCCEEDED 일 때만 채워지며 직렬화 시 null 은 생략된다.
 * {@code failureReason} 은 SOLD_OUT / INTERNAL_ERROR / 상태 거절 시에만 채워진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IssueCouponResponse(
    String requestId,
    IssueRequestStatus status,
    String couponCode,
    String failureReason
) {

    public static IssueCouponResponse from(IssueRequest req) {
        String code = req.getCouponCode() == null ? null : req.getCouponCode().value();
        return new IssueCouponResponse(req.getRequestId(), req.getStatus(), code, req.getFailureReason());
    }
}
