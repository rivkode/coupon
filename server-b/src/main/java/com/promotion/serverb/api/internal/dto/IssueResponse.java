package com.promotion.serverb.api.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.promotion.common.coupon.IssueResult;
import com.promotion.common.coupon.IssueStatus;

/**
 * server-a 가 받는 internal 발급 응답 본문.
 *
 * <p>server-a 의 {@code RestClientCouponIssuingClient.IssueResponsePayload} 와 정합 —
 * 변경 시 양쪽 동시 갱신. 정상 / 실패 모든 경우 본 형식 통일 (HTTP status 만 다름).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IssueResponse(IssueStatus status, String couponCode, String failureReason) {

    public static IssueResponse from(IssueResult result) {
        String code = result.couponCode() == null ? null : result.couponCode().value();
        return new IssueResponse(result.status(), code, result.failureReason());
    }

    public static IssueResponse failure(String reason) {
        return new IssueResponse(IssueStatus.INTERNAL_ERROR, null, reason);
    }
}
