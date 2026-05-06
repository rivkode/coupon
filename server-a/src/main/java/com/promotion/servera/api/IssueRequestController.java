package com.promotion.servera.api;

import com.promotion.servera.api.dto.ApiResponse;
import com.promotion.servera.api.dto.IssueCouponRequest;
import com.promotion.servera.api.dto.IssueCouponResponse;
import com.promotion.servera.application.IssueCommand;
import com.promotion.servera.application.IssueRequestService;
import com.promotion.servera.domain.IssueRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 발급 요청 진입점.
 *
 * <p>Headers:
 * <ul>
 *   <li>{@code X-User-Id} (필수) — 인증 단순화. Day 1 은 JWT 미도입.</li>
 *   <li>{@code Idempotency-Key} (필수) — UUID. PR #5 (Phase 7) 에서 Filter 가 검증/캐싱.</li>
 * </ul>
 *
 * <p>HTTP 상태 매핑 (Day 1):
 * <ul>
 *   <li>SUCCEEDED → 200</li>
 *   <li>FAILED (Server B 응답 SOLD_OUT / INTERNAL_ERROR) → 200, body 의 {@code status} 로 구분</li>
 * </ul>
 * Phase 8 Circuit Breaker 진입 시 INTERNAL_ERROR 는 503 으로 격상 검토.
 */
@RestController
@RequestMapping("/api/v1/coupons/issue-requests")
@RequiredArgsConstructor
public class IssueRequestController {

    private final IssueRequestService issueRequestService;

    @PostMapping
    public ResponseEntity<ApiResponse<IssueCouponResponse>> issue(
        @RequestHeader("X-User-Id") Long userId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody IssueCouponRequest body
    ) {
        IssueRequest result = issueRequestService.issue(IssueCommand.of(userId, idempotencyKey, body));
        return ResponseEntity.status(HttpStatus.OK)
            .body(ApiResponse.success(IssueCouponResponse.from(result)));
    }
}
