package com.promotion.common.coupon;

/** C → B 의 발급 결과 상태. (server-c 의 UserCouponStatus 와 별개의 wire-level enum) */
public enum CouponIssueResultStatus {
    SUCCESS,
    SOLD_OUT,
    FAILED
}
