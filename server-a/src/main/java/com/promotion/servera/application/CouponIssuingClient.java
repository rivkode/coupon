package com.promotion.servera.application;

import com.promotion.common.coupon.IssueAcceptanceResult;

/** Server A → Server B 호출 포트. */
public interface CouponIssuingClient {

    IssueAcceptanceResult issue(long userId, long eventId, long couponTypeId);
}
