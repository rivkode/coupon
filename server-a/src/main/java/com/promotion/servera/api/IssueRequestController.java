package com.promotion.servera.api;

import com.promotion.servera.api.dto.ApiResponse;
import com.promotion.servera.api.dto.IssueCouponRequest;
import com.promotion.servera.api.dto.IssueCouponResponse;
import com.promotion.servera.application.IssueCommand;
import com.promotion.servera.application.IssueOutcome;
import com.promotion.servera.application.IssueRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 발급 요청 진입점 (신규 설계 — 즉시 "접수 완료" 응답).
 *
 * <p>매핑:
 * <ul>
 *   <li>200 OK — ACCEPTED 또는 DUPLICATE (사용자에겐 같은 200, 결과는 폴링/내정보)</li>
 *   <li>503 + Retry-After — Circuit Breaker OPEN / B 일시 장애</li>
 *   <li>400 — 헤더 누락 / body invalid</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class IssueRequestController {

    private static final String RETRY_AFTER_SECONDS = "5";

    private final IssueRequestService service;

    @PostMapping("/issue-request")
    public ResponseEntity<ApiResponse<IssueCouponResponse>> issue(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody IssueCouponRequest request
    ) {
        IssueOutcome outcome = service.issue(
                new IssueCommand(userId, request.eventId(), request.couponTypeId()));
        IssueCouponResponse payload = IssueCouponResponse.from(outcome);
        if (outcome.isDownstreamUnavailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .body(ApiResponse.success(payload));
        }
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success(payload));
    }
}
