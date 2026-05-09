package com.promotion.common.coupon;

/** Server A 의 IssueRequestService 에서 사용. B 호출 결과를 A 가 사용자에게 전달하기 위한 형. */
public record IssueAcceptanceResult(
        String requestId,
        IssueAcceptanceStatus status,
        String message
) {
    public static IssueAcceptanceResult accepted(String requestId) {
        return new IssueAcceptanceResult(requestId, IssueAcceptanceStatus.ACCEPTED, null);
    }

    public static IssueAcceptanceResult duplicate(String requestId) {
        return new IssueAcceptanceResult(requestId, IssueAcceptanceStatus.DUPLICATE,
                "already requested for this coupon type");
    }

    public static IssueAcceptanceResult internalError(String reason) {
        return new IssueAcceptanceResult(null, IssueAcceptanceStatus.INTERNAL_ERROR, reason);
    }
}
