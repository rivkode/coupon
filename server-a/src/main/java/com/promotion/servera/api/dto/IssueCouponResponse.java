package com.promotion.servera.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.promotion.common.coupon.IssueAcceptanceStatus;
import com.promotion.servera.application.IssueOutcome;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IssueCouponResponse(
        String requestId,
        IssueAcceptanceStatus status,
        String message
) {
    public static IssueCouponResponse from(IssueOutcome outcome) {
        return new IssueCouponResponse(
                outcome.issueRequest().getRequestId(),
                outcome.downstreamStatus(),
                outcome.message()
        );
    }
}
