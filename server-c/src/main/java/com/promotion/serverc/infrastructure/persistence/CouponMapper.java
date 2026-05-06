package com.promotion.serverc.infrastructure.persistence;

import com.promotion.common.coupon.CouponCode;
import com.promotion.serverc.domain.Coupon;

final class CouponMapper {

    private CouponMapper() {
    }

    static Coupon toDomain(CouponJpaEntity entity) {
        return Coupon.reconstitute(
            entity.getId(),
            new CouponCode(entity.getCode()),
            entity.getUserId(),
            entity.getEventId(),
            entity.getIdempotencyKey(),
            entity.getIssuedAt(),
            entity.getUsedAt(),
            entity.getVersion()
        );
    }

    static CouponJpaEntity toEntity(Coupon domain) {
        return CouponJpaEntity.builder()
            .id(domain.getId())
            .code(domain.getCode().value())
            .userId(domain.getUserId())
            .eventId(domain.getEventId())
            .idempotencyKey(domain.getIdempotencyKey())
            .issuedAt(domain.getIssuedAt())
            .usedAt(domain.getUsedAt())
            .version(domain.getVersion())
            .build();
    }
}
