package com.promotion.serverc.api;

import com.promotion.serverc.api.dto.ApiResponse;
import com.promotion.serverc.api.dto.UserCouponResponse;
import com.promotion.serverc.application.UserCouponQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 사용자 본인의 쿠폰 목록 조회 (public).
 *
 * <p>"내 자원" endpoint 라 path 에 userId 를 두지 않고 X-User-Id 헤더가 사용자를 결정한다 (`/me` 패턴).
 * GitHub `/users/me/...`, Microsoft Graph `/me/...` 등의 관행. 인증은 CLAUDE.md §5.1 의 헤더 기반 가정.
 *
 * <p>매핑:
 * <ul>
 *   <li>200 OK — 본인의 쿠폰 목록 (issued_at DESC)</li>
 *   <li>400 — X-User-Id 헤더 누락</li>
 * </ul>
 *
 * <p>페이지네이션은 사용자당 이벤트 상한이 100 (§1) 이라 적용하지 않음 (scope-discipline).
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserCouponController {

    private final UserCouponQueryService queryService;

    @GetMapping("/coupons")
    public ResponseEntity<ApiResponse<List<UserCouponResponse>>> findMine(
            @RequestHeader("X-User-Id") long userId
    ) {
        List<UserCouponResponse> body = queryService.findAllByUserId(userId).stream()
                .map(UserCouponResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(body));
    }
}
