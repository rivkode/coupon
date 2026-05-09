package com.promotion.serverc.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.promotion.serverc.domain.UserCouponStatus;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaEntity;

import java.time.LocalDateTime;

/** Server B 의 @Scheduled 가 호출하는 internal GET 의 응답. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserCouponInternalResponse(
        long userId,
        long eventId,
        long couponTypeId,
        String code,
        UserCouponStatus status,
        LocalDateTime issuedAt
) {
    public static UserCouponInternalResponse from(UserCouponJpaEntity uc) {
        return new UserCouponInternalResponse(
                uc.getUserId(),
                uc.getEventId(),
                uc.getCouponTypeId(),
                uc.getCode(),
                uc.getStatus(),
                uc.getIssuedAt()
        );
    }
}
