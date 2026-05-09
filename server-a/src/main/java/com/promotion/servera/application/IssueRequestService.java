package com.promotion.servera.application;

import com.promotion.common.coupon.IssueAcceptanceResult;
import com.promotion.common.coupon.IssueAcceptanceStatus;
import com.promotion.servera.domain.IssueRequest;
import com.promotion.servera.domain.IssueRequestRepository;
import com.promotion.servera.domain.IssueRequestStatus;
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
 *   <li>Server B 호출 (sync, Resilience4j Circuit Breaker)</li>
 *   <li>응답 결과(ACCEPTED / DUPLICATE / INTERNAL_ERROR) 에 따라 IssueRequest 도메인 status 결정</li>
 *   <li>per-request commit 으로 issue_request 적재 (audit)</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class IssueRequestService {

    private static final Logger log = LoggerFactory.getLogger(IssueRequestService.class);

    private final IssueRequestRepository repository;
    private final CouponIssuingClient client;

    @Transactional
    public IssueOutcome issue(IssueCommand cmd) {
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
            case INTERNAL_ERROR -> IssueRequestStatus.REJECTED;
        };
    }
}
