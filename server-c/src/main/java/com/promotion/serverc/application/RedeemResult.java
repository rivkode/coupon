package com.promotion.serverc.application;

import com.promotion.common.coupon.CouponCode;
import java.time.Instant;
import java.util.Objects;

/**
 * 쿠폰 사용 결과. {@code newlyRedeemed} 가 false 면 같은 user 의 멱등 재호출로 기존 redeemedAt 반환.
 */
public record RedeemResult(CouponCode code, long userId, Instant redeemedAt, boolean newlyRedeemed) {

    public RedeemResult {
        Objects.requireNonNull(code, "code");
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive but was " + userId);
        }
        Objects.requireNonNull(redeemedAt, "redeemedAt");
    }

    public static RedeemResult succeeded(CouponCode code, long userId, Instant redeemedAt) {
        return new RedeemResult(code, userId, redeemedAt, true);
    }

    public static RedeemResult alreadyRedeemed(CouponCode code, long userId, Instant redeemedAt) {
        return new RedeemResult(code, userId, redeemedAt, false);
    }
}
