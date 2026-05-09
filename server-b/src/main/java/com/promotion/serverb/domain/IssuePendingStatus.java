package com.promotion.serverb.domain;

/** Redis 의 user 신청 상태. server-c 의 UserCouponStatus 와 별개의 wire-level. */
public enum IssuePendingStatus {
    PENDING,
    SUCCESS,
    SOLD_OUT,
    FAILED
}
