package com.promotion.serverb.api.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.promotion.common.coupon.IssueAcceptanceResult;
import com.promotion.common.coupon.IssueAcceptanceStatus;

/**
 * server-a 가 받는 internal 발급 응답 본문 (신규 설계 — "접수 완료" 응답 모델).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IssueResponse(String requestId, IssueAcceptanceStatus status, String message) {

    public static IssueResponse from(IssueAcceptanceResult result) {
        return new IssueResponse(result.requestId(), result.status(), result.message());
    }
}
