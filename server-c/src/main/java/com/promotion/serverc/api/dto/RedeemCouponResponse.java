package com.promotion.serverc.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.promotion.serverc.application.RedeemResult;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedeemCouponResponse(
        String code,
        long userId,
        LocalDateTime redeemedAt,
        boolean newlyRedeemed
) {
    public static RedeemCouponResponse from(RedeemResult result) {
        return new RedeemCouponResponse(
                result.code(),
                result.userId(),
                result.redeemedAt(),
                result.newlyRedeemed()
        );
    }
}
