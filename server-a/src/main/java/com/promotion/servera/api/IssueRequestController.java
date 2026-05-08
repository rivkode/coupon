package com.promotion.servera.api;

import com.promotion.servera.api.dto.ApiResponse;
import com.promotion.servera.api.dto.IssueCouponRequest;
import com.promotion.servera.api.dto.IssueCouponResponse;
import com.promotion.servera.application.IssueCommand;
import com.promotion.servera.application.IssueOutcome;
import com.promotion.servera.application.IssueRequestService;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
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
 * 발급 요청 진입점.
 *
 * <p>Headers:
 * <ul>
 *   <li>{@code X-User-Id} (필수) — 인증 단순화. Day 1 은 JWT 미도입.</li>
 *   <li>{@code Idempotency-Key} (필수) — UUID. PR #6 의 IdempotencyFilter 가 검증/캐싱.</li>
 * </ul>
 *
 * <p>HTTP 상태 매핑:
 * <ul>
 *   <li>downstreamStatus = ISSUED / ALREADY_ISSUED / SOLD_OUT → 200 (body 의 {@code status} 로 구분)</li>
 *   <li>downstreamStatus = INTERNAL_ERROR (Circuit OPEN / timeout / 5xx) → 503 + {@code Retry-After}</li>
 * </ul>
 * 503 을 idempotency 캐시에 저장하지 않도록 IdempotencyFilter 에서 2xx 만 캐싱.
 */
@RestController
@RequestMapping("/api/v1/coupons/issue-requests")
@RequiredArgsConstructor
public class IssueRequestController {

    private static final String RETRY_AFTER_SECONDS = "5";

    private final IssueRequestService issueRequestService;

    /**
     * Phase C — Bulkhead admission control (CLAUDE.md ADR-005, 보고서 §5.2).
     * max-concurrent-calls 초과 시 BulkheadFullException → GlobalExceptionHandler 가 503 + Retry-After 매핑.
     * 큐 없음 (max-wait=0) — 시스템 capacity 초과 트래픽을 즉시 거부 (Tomcat queue 와 두 단계 큐 회피).
     */
    @PostMapping
    @Bulkhead(name = "issueRequestBulkhead", type = Bulkhead.Type.SEMAPHORE)
    public ResponseEntity<ApiResponse<IssueCouponResponse>> issue(
        @RequestHeader("X-User-Id") Long userId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody IssueCouponRequest body
    ) {
        IssueOutcome outcome = issueRequestService.issue(IssueCommand.of(userId, idempotencyKey, body));
        IssueCouponResponse payload = IssueCouponResponse.from(outcome.issueRequest());
        if (outcome.isDownstreamUnavailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ApiResponse.success(payload));
        }
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success(payload));
    }
}
