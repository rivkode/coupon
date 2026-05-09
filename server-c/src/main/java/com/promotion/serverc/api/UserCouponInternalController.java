package com.promotion.serverc.api;

import com.promotion.serverc.api.dto.ApiResponse;
import com.promotion.serverc.api.dto.UserCouponInternalResponse;
import com.promotion.serverc.application.UserCouponQueryService;
import com.promotion.serverc.domain.exception.CouponNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server B 의 @Scheduled 가 호출하는 internal API (ADR-008).
 *
 * <p>10 초 이상 pending 인 신청에 대해 B 가 본 endpoint 로 직접 조회 → 결과를 Redis 에 반영.
 */
@RestController
@RequestMapping("/internal/v1/users")
@RequiredArgsConstructor
public class UserCouponInternalController {

    private final UserCouponQueryService queryService;

    @GetMapping("/{userId}/coupons/{couponTypeId}")
    public ResponseEntity<ApiResponse<UserCouponInternalResponse>> findOne(
            @PathVariable("userId") long userId,
            @PathVariable("couponTypeId") long couponTypeId
    ) {
        return queryService.findByUserIdAndCouponTypeId(userId, couponTypeId)
                .map(uc -> ResponseEntity.ok(ApiResponse.success(UserCouponInternalResponse.from(uc))))
                .orElseThrow(() -> new CouponNotFoundException(
                        "user_coupon not found: userId=" + userId + ", couponTypeId=" + couponTypeId));
    }
}
