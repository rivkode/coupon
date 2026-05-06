package com.promotion.serverb.infrastructure.redis;

import com.promotion.common.coupon.CouponCode;

/**
 * issue-coupon.lua 의 결과 wrapper.
 *
 * <p>{@code couponCode} 는 status 가 {@link LuaIssueStatus#ISSUED} 또는
 * {@link LuaIssueStatus#ALREADY_ISSUED} 일 때만 non-null.
 */
public record LuaIssueResult(LuaIssueStatus status, CouponCode couponCode) {

    public static LuaIssueResult issued(CouponCode code) {
        return new LuaIssueResult(LuaIssueStatus.ISSUED, code);
    }

    public static LuaIssueResult alreadyIssued(CouponCode code) {
        return new LuaIssueResult(LuaIssueStatus.ALREADY_ISSUED, code);
    }

    public static LuaIssueResult soldOut() {
        return new LuaIssueResult(LuaIssueStatus.SOLD_OUT, null);
    }

    public static LuaIssueResult codeCollision() {
        return new LuaIssueResult(LuaIssueStatus.CODE_COLLISION, null);
    }

    public boolean hasCode() {
        return couponCode != null;
    }
}
