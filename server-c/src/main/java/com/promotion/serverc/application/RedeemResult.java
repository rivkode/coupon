package com.promotion.serverc.application;

import java.time.LocalDateTime;
import java.util.Objects;

/** newlyRedeemed=false 면 같은 user 의 멱등 재호출. */
public record RedeemResult(String code, long userId, LocalDateTime redeemedAt, boolean newlyRedeemed) {

    public RedeemResult {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(redeemedAt, "redeemedAt");
    }

    public static RedeemResult succeeded(String code, long userId, LocalDateTime redeemedAt) {
        return new RedeemResult(code, userId, redeemedAt, true);
    }

    public static RedeemResult alreadyRedeemed(String code, long userId, LocalDateTime redeemedAt) {
        return new RedeemResult(code, userId, redeemedAt, false);
    }
}
