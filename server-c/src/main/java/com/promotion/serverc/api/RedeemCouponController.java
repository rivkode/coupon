package com.promotion.serverc.api;

import com.promotion.serverc.api.dto.ApiResponse;
import com.promotion.serverc.api.dto.RedeemCouponResponse;
import com.promotion.serverc.application.RedeemCommand;
import com.promotion.serverc.application.RedeemCouponService;
import com.promotion.serverc.application.RedeemResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 쿠폰 사용 (CLAUDE.md ADR-007). 신규 설계 — Idempotency-Key 헤더 미사용.
 *
 * <p>매핑:
 * <ul>
 *   <li>200 OK — 정상 redeem 또는 같은 user 의 멱등 재호출</li>
 *   <li>404 — 코드 없음 / 다른 user (마스킹)</li>
 *   <li>409 — 낙관락 race / 사용 불가 상태</li>
 *   <li>400 — 헤더 누락 / path invalid</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class RedeemCouponController {

    private final RedeemCouponService redeemCouponService;

    @PostMapping("/{code}/redeem")
    public ResponseEntity<ApiResponse<RedeemCouponResponse>> redeem(
            @PathVariable("code") String code,
            @RequestHeader("X-User-Id") Long userId
    ) {
        RedeemResult result = redeemCouponService.redeem(new RedeemCommand(code, userId));
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.success(RedeemCouponResponse.from(result)));
    }
}
