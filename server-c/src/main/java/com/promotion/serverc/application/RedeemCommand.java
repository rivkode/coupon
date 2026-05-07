package com.promotion.serverc.application;

import com.promotion.common.coupon.CouponCode;
import java.util.Objects;

/**
 * 쿠폰 사용 요청. CouponCode VO 가 길이/문자 검증을 책임.
 *
 * <p>{@code idempotencyKey} 는 trace/log 용. redeem 의 결과는 본질적으로 멱등이라 별도 캐시
 * 없이 도메인 자체로 멱등성을 만족 (CLAUDE.md ADR-004 / ADR-007).
 */
public record RedeemCommand(CouponCode code, long userId, String idempotencyKey) {

    public RedeemCommand {
        Objects.requireNonNull(code, "code");
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive but was " + userId);
        }
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
