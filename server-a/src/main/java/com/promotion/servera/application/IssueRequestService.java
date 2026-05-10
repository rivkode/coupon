package com.promotion.servera.application;

import com.promotion.common.coupon.IssueAcceptanceResult;
import com.promotion.common.coupon.IssueAcceptanceStatus;
import com.promotion.servera.domain.IssueRequest;
import com.promotion.servera.domain.IssueRequestRepository;
import com.promotion.servera.domain.IssueRequestStatus;
import com.promotion.servera.infrastructure.redis.CouponAvailabilityCache;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 발급 요청 처리 (CLAUDE.md ADR-010 — per-request commit).
 *
 * <p>흐름:
 * <ol>
 *   <li>ADR-011 SOLD_OUT negative cache 체크 → 매진이면 B 호출 없이 즉시 SOLD_OUT 단락</li>
 *   <li>Server B 호출 (sync, Resilience4j Circuit Breaker)</li>
 *   <li>응답 결과(ACCEPTED / DUPLICATE / SOLD_OUT / INTERNAL_ERROR) 에 따라 IssueRequest 도메인 status 결정</li>
 *   <li>per-request commit 으로 issue_request 적재 (audit)</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class IssueRequestService {

    private static final Logger log = LoggerFactory.getLogger(IssueRequestService.class);

    private final IssueRequestRepository repository;
    private final CouponIssuingClient client;
    private final CouponAvailabilityCache availabilityCache;

    @Transactional
    public IssueOutcome issue(IssueCommand cmd) {
        if (availabilityCache.isSoldOut(cmd.eventId(), cmd.couponTypeId())) {
            IssueRequest req = repository.save(IssueRequest.of(
                    cmd.userId(), cmd.eventId(), cmd.couponTypeId(),
                    IssueRequestStatus.SOLD_OUT, Instant.now()));
            log.info("short-circuit sold out at A (cache hit): requestId={}, userId={}, couponTypeId={}",
                    req.getRequestId(), cmd.userId(), cmd.couponTypeId());
            return new IssueOutcome(req, IssueAcceptanceStatus.SOLD_OUT, "coupon sold out");
        }
        IssueAcceptanceResult result = client.issue(cmd.userId(), cmd.eventId(), cmd.couponTypeId());
        IssueRequestStatus status = mapStatus(result.status());
        IssueRequest req = repository.save(IssueRequest.of(
                cmd.userId(), cmd.eventId(), cmd.couponTypeId(), status, Instant.now()));
        log.info("issue accepted: requestId={}, userId={}, couponTypeId={}, status={}",
                req.getRequestId(), cmd.userId(), cmd.couponTypeId(), status);
        return new IssueOutcome(req, result.status(), result.message());
    }

    private static IssueRequestStatus mapStatus(IssueAcceptanceStatus s) {
        return switch (s) {
            case ACCEPTED -> IssueRequestStatus.ACCEPTED;
            case DUPLICATE -> IssueRequestStatus.DUPLICATE;
            case SOLD_OUT -> IssueRequestStatus.SOLD_OUT;
            case INTERNAL_ERROR -> IssueRequestStatus.REJECTED;
        };
    }
}
