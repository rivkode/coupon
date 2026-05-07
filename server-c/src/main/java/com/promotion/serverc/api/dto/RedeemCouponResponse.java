package com.promotion.serverc.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.promotion.serverc.application.RedeemResult;
import java.time.Instant;

/**
 * 쿠폰 사용 응답. {@code newlyRedeemed=false} 면 같은 user 의 멱등 재호출.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedeemCouponResponse(
    String code,
    long userId,
    Instant redeemedAt,
    boolean newlyRedeemed
) {

    public static RedeemCouponResponse from(RedeemResult result) {
        return new RedeemCouponResponse(
            result.code().value(),
            result.userId(),
            result.redeemedAt(),
            result.newlyRedeemed()
        );
    }
}
