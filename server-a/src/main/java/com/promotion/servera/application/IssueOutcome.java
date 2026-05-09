package com.promotion.servera.application;

import com.promotion.common.coupon.IssueAcceptanceStatus;
import com.promotion.servera.domain.IssueRequest;

/** Service 결과. Controller 가 HTTP status 매핑에 사용. */
public record IssueOutcome(IssueRequest issueRequest, IssueAcceptanceStatus downstreamStatus, String message) {

    public boolean isDownstreamUnavailable() {
        return downstreamStatus == IssueAcceptanceStatus.INTERNAL_ERROR;
    }
}
