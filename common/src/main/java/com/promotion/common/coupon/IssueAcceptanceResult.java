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

    /**
     * SOLD_OUT 단락 — Redis pending 적재 없이 즉시 반환되므로 추적 가능한 requestId 가 없다 (null).
     * 사용자는 폴링/내쿠폰 조회 동선이 필요 없음 (응답 자체가 종결).
     */
    public static IssueAcceptanceResult soldOut() {
        return new IssueAcceptanceResult(null, IssueAcceptanceStatus.SOLD_OUT,
                "coupon sold out");
    }

    public static IssueAcceptanceResult internalError(String reason) {
        return new IssueAcceptanceResult(null, IssueAcceptanceStatus.INTERNAL_ERROR, reason);
    }
}
