package com.promotion.common.coupon;

/**
 * Server B 발급 호출 결과. Server A ↔ Server B 양쪽에서 사용.
 * couponCode 는 status == ISSUED || status == ALREADY_ISSUED 일 때만 non-null.
 * failureReason 은 status == SOLD_OUT || status == INTERNAL_ERROR 일 때만 non-null.
 */
public record IssueResult(
    CouponCode couponCode,
    IssueStatus status,
    String failureReason
) {
    public static IssueResult issued(CouponCode code) {
        return new IssueResult(code, IssueStatus.ISSUED, null);
    }

    public static IssueResult alreadyIssued(CouponCode code) {
        return new IssueResult(code, IssueStatus.ALREADY_ISSUED, null);
    }

    public static IssueResult soldOut() {
        return new IssueResult(null, IssueStatus.SOLD_OUT, "stock exhausted");
    }

    public static IssueResult internalError(String reason) {
        return new IssueResult(null, IssueStatus.INTERNAL_ERROR, reason);
    }

    public boolean isSuccess() {
        return status == IssueStatus.ISSUED || status == IssueStatus.ALREADY_ISSUED;
    }
}
