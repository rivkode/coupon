package com.promotion.serverc.domain;

import com.promotion.common.coupon.CouponCode;
import java.util.List;
import java.util.Optional;

public interface CouponRepository {

    Coupon save(Coupon coupon);

    Optional<Coupon> findByCode(CouponCode code);

    Optional<Coupon> findByIdempotencyKey(String idempotencyKey);

    List<Coupon> findByUserId(Long userId);
}
