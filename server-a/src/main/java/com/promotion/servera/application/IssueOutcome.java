package com.promotion.servera.application;

import com.promotion.common.coupon.IssueStatus;
import com.promotion.servera.domain.IssueRequest;

/**
 * Server A 발급 처리 결과.
 *
 * <p>{@link IssueRequest} 의 상태(SUCCEEDED/FAILED/REJECTED)는 Server A 의 도메인 상태이고,
 * {@code downstreamStatus} 는 Server B 의 응답 코드(ISSUED/SOLD_OUT/INTERNAL_ERROR/...) 다.
 * Controller 의 HTTP 상태 매핑은 downstreamStatus 를 봐야 정확하다 — 같은 FAILED 라도
 * SOLD_OUT 은 200, INTERNAL_ERROR(circuit-open / timeout) 은 503.
 */
public record IssueOutcome(IssueRequest issueRequest, IssueStatus downstreamStatus) {

    public boolean isDownstreamUnavailable() {
        return downstreamStatus == IssueStatus.INTERNAL_ERROR;
    }
}
