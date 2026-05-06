package com.promotion.servera.application;

import com.promotion.common.coupon.IssueResult;
import com.promotion.servera.domain.Event;
import com.promotion.servera.domain.EventRepository;
import com.promotion.servera.domain.IssueRequest;
import com.promotion.servera.domain.IssueRequestRepository;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 발급 요청 orchestration.
 *
 * <p><b>트랜잭션 전략 (CLAUDE.md ADR-001 / 안티패턴 §10)</b>
 * <ul>
 *   <li>tx1 — RECEIVED 영속화 (감사 / 멱등성 1차 캐시)</li>
 *   <li>외부 호출 — Server B (Phase 6 단계 stub) — <b>tx 외부에서 수행</b></li>
 *   <li>tx2 — FORWARDED → SUCCEEDED / FAILED 로 마감</li>
 * </ul>
 * Spring AOP 의 self-invocation 한계 회피를 위해 {@code TransactionTemplate} 명시 사용.
 *
 * <p>Phase 6 시점엔 Idempotency Filter 가 없어 같은 키 재요청은 DB UNIQUE constraint
 * 위반(DataIntegrityViolation)이 직접 노출된다. PR #5 (Phase 7) 에서 Filter 가 앞단에서 차단.
 */
@Service
@RequiredArgsConstructor
public class IssueRequestService {

    private static final Logger log = LoggerFactory.getLogger(IssueRequestService.class);

    private final IssueRequestRepository issueRequestRepository;
    private final EventRepository eventRepository;
    private final CouponIssuingClient couponIssuingClient;
    private final TransactionTemplate transactionTemplate;

    public IssueRequest issue(IssueCommand cmd) {
        Instant now = Instant.now();

        Event event = eventRepository.findById(cmd.eventId())
            .orElseThrow(() -> new IllegalArgumentException("event not found: " + cmd.eventId()));
        if (!event.isOpenAt(now)) {
            throw new IllegalStateException("event is not open: " + cmd.eventId());
        }

        IssueRequest received = Objects.requireNonNull(
            transactionTemplate.execute(status ->
                issueRequestRepository.save(IssueRequest.received(
                    cmd.userId(), cmd.eventId(), cmd.idempotencyKey(), now))),
            "tx1 returned null");

        IssueResult result;
        try {
            result = couponIssuingClient.issue(cmd.userId(), cmd.eventId(), cmd.idempotencyKey());
        } catch (RuntimeException ex) {
            log.warn("coupon issuing client threw: requestId={} reason={}",
                received.getRequestId(), ex.getMessage());
            result = IssueResult.internalError("client-exception: " + ex.getClass().getSimpleName());
        }

        IssueResult finalResult = result;
        try {
            return Objects.requireNonNull(
                transactionTemplate.execute(status -> {
                    received.markForwarded();
                    if (finalResult.isSuccess()) {
                        received.markSucceeded(finalResult.couponCode());
                    } else {
                        received.markFailed(finalResult.failureReason());
                    }
                    return issueRequestRepository.save(received);
                }),
                "tx2 returned null");
        } catch (RuntimeException tx2Ex) {
            // 외부 호출은 성공했으나 마감 tx 실패 — couponCode 가 실제 발급되었는지 사후 reconcile
            // 가능하도록 식별 정보 일괄 로깅. PR #5 (Idempotency Filter) 진입 시에도 동일 키 재요청은
            // DB UNIQUE constraint 가 차단 → 결과적으로 안전하나, 로그 단서가 없으면 추적 불가.
            String code = finalResult.couponCode() == null ? null : finalResult.couponCode().value();
            log.error(
                "issue finalize tx2 failed; manual reconcile may be needed: requestId={} userId={} idempotencyKey={} clientStatus={} couponCode={}",
                received.getRequestId(), cmd.userId(), cmd.idempotencyKey(),
                finalResult.status(), code, tx2Ex);
            throw tx2Ex;
        }
    }
}
