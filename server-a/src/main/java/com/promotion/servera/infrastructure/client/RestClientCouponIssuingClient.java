package com.promotion.servera.infrastructure.client;

import com.promotion.common.coupon.IssueAcceptanceResult;
import com.promotion.common.coupon.IssueAcceptanceStatus;
import com.promotion.servera.application.CouponIssuingClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Server B `POST /internal/v1/coupons/issue` 호출 (ADR-001). */
@Component
@RequiredArgsConstructor
public class RestClientCouponIssuingClient implements CouponIssuingClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientCouponIssuingClient.class);
    private static final String CB_NAME = "couponIssuing";

    private final RestClient couponIssuingRestClient;

    @Override
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "issueFallback")
    public IssueAcceptanceResult issue(long userId, long eventId, long couponTypeId) {
        IssueRequestPayload payload = new IssueRequestPayload(eventId, couponTypeId);
        IssueResponsePayload responseBody = couponIssuingRestClient.post()
                .uri("/internal/v1/coupons/issue")
                .header("X-User-Id", String.valueOf(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(IssueResponsePayload.class);

        if (responseBody == null || responseBody.status() == null) {
            log.warn("server-b empty body: userId={} couponTypeId={}", userId, couponTypeId);
            return IssueAcceptanceResult.internalError("empty-response");
        }
        return new IssueAcceptanceResult(responseBody.requestId(), responseBody.status(), responseBody.message());
    }

    @SuppressWarnings("unused")
    IssueAcceptanceResult issueFallback(long userId, long eventId, long couponTypeId, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.warn("circuit OPEN: userId={} couponTypeId={}", userId, couponTypeId);
            return IssueAcceptanceResult.internalError("circuit-open");
        }
        if (t instanceof RestClientResponseException rre) {
            log.warn("server-b status={}: userId={} couponTypeId={}", rre.getStatusCode(), userId, couponTypeId);
            return IssueAcceptanceResult.internalError("server-b-status:" + rre.getStatusCode().value());
        }
        log.warn("server-b call failed: userId={} couponTypeId={} reason={}",
                userId, couponTypeId, t.toString());
        return IssueAcceptanceResult.internalError("downstream-error:" + t.getClass().getSimpleName());
    }

    private record IssueRequestPayload(long eventId, long couponTypeId) {}

    private record IssueResponsePayload(String requestId, IssueAcceptanceStatus status, String message) {}
}
