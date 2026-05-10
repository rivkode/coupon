package com.promotion.serverc.api.dto;

import com.promotion.serverc.domain.UserCouponStatus;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaEntity;

import java.time.LocalDateTime;

/**
 * 사용자 본인의 쿠폰 목록 조회 응답 element.
 *
 * <p>usedAt 은 미사용 쿠폰에서 의미 있게 null — 직렬화 누락을 막기 위해 NON_NULL 필터를 쓰지 않음.
 */
public record UserCouponResponse(
        long userId,
        long eventId,
        long couponTypeId,
        String code,
        UserCouponStatus status,
        LocalDateTime issuedAt,
        LocalDateTime usedAt
) {
    public static UserCouponResponse from(UserCouponJpaEntity uc) {
        return new UserCouponResponse(
                uc.getUserId(),
                uc.getEventId(),
                uc.getCouponTypeId(),
                uc.getCode(),
                uc.getStatus(),
                uc.getIssuedAt(),
                uc.getUsedAt()
        );
    }
}
