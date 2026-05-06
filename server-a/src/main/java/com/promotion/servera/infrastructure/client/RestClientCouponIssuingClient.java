package com.promotion.servera.infrastructure.client;

import com.promotion.common.coupon.CouponCode;
import com.promotion.common.coupon.IssueResult;
import com.promotion.common.coupon.IssueStatus;
import com.promotion.servera.application.CouponIssuingClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 실제 Server B 호출 구현체. {@code @Profile("!local")} 로 default / dev / prod 프로파일에서 활성화.
 *
 * <p>CLAUDE.md ADR-001 + ADR-005 적용:
 * <ul>
 *   <li>HTTP timeout — connect 1s, read 200ms (RestClientConfig 참조)</li>
 *   <li>Resilience4j Circuit Breaker (`couponIssuing`) — application.yml 정의</li>
 *   <li>fallback — 어떤 예외든 {@link IssueResult#internalError} 로 매핑하여 호출자가 일관 처리</li>
 * </ul>
 *
 * <p>Server B API: {@code POST /internal/v1/coupons/issue} — body {@code {userId, eventId,
 * idempotencyKey}}, response {@code {status, couponCode}} (Day 2 에서 Server B 측 정의 예정).
 */
@Component
@Profile("!local")
public class RestClientCouponIssuingClient implements CouponIssuingClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientCouponIssuingClient.class);
    private static final String CB_NAME = "couponIssuing";

    private final RestClient restClient;

    public RestClientCouponIssuingClient(RestClient couponIssuingRestClient) {
        this.restClient = couponIssuingRestClient;
    }

    @Override
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "issueFallback")
    public IssueResult issue(Long userId, Long eventId, String idempotencyKey) {
        IssueRequestPayload payload = new IssueRequestPayload(userId, eventId, idempotencyKey);
        IssueResponsePayload body = restClient.post()
            .uri("/internal/v1/coupons/issue")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .body(IssueResponsePayload.class);

        if (body == null || body.status() == null) {
            log.warn("server-b returned empty body: userId={} eventId={}", userId, eventId);
            return IssueResult.internalError("empty-response");
        }
        return toIssueResult(body);
    }

    @SuppressWarnings("unused") // Resilience4j 가 리플렉션으로 호출 — 시그니처 일치 필수.
    private IssueResult issueFallback(Long userId, Long eventId, String idempotencyKey, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.warn("circuit breaker OPEN: userId={} eventId={}", userId, eventId);
            return IssueResult.internalError("circuit-open");
        }
        if (t instanceof RestClientResponseException rre) {
            log.warn("server-b returned {}: userId={} eventId={} body={}",
                rre.getStatusCode(), userId, eventId, rre.getResponseBodyAsString());
            return IssueResult.internalError("server-b-status:" + rre.getStatusCode().value());
        }
        log.warn("server-b call failed: userId={} eventId={} reason={}", userId, eventId, t.toString());
        return IssueResult.internalError("downstream-error:" + t.getClass().getSimpleName());
    }

    private IssueResult toIssueResult(IssueResponsePayload body) {
        IssueStatus status = body.status();
        return switch (status) {
            case ISSUED -> IssueResult.issued(new CouponCode(body.couponCode()));
            case ALREADY_ISSUED -> IssueResult.alreadyIssued(new CouponCode(body.couponCode()));
            case SOLD_OUT -> IssueResult.soldOut();
            case INTERNAL_ERROR -> IssueResult.internalError(
                body.failureReason() == null ? "server-b-internal" : body.failureReason());
        };
    }

    private record IssueRequestPayload(Long userId, Long eventId, String idempotencyKey) {}

    private record IssueResponsePayload(IssueStatus status, String couponCode, String failureReason) {}
}
