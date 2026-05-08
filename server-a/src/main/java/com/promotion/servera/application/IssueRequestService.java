package com.promotion.servera.application;

import com.promotion.common.coupon.IssueResult;
import com.promotion.servera.domain.Event;
import com.promotion.servera.domain.EventRepository;
import com.promotion.servera.domain.IssueRequest;
import com.promotion.servera.domain.IssueRequestRepository;
import com.promotion.servera.infrastructure.batch.IssueRequestBatchQueue;
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
 * <p><b>Phase C 의 트랜잭션 전략 (보고서 §5.1, server-a-tuning §3.3)</b>
 * <p>이전 (Day 3) 까지: tx1 (RECEIVED save) + 외부 호출 + tx2 (마감 save) — 요청당 2 commit.
 * Phase C 부터: 외부 호출 → 도메인 객체 in-memory 마감 → batch queue 에 1회 enqueue.
 * <ul>
 *   <li>큐가 가득 차면 동기 INSERT fallback (방어선 2) — graceful degradation</li>
 *   <li>큐가 정상이면 200ms 마다 flusher 가 batch INSERT — 요청당 부분 commit</li>
 *   <li>RECEIVED 중간 상태는 DB 에 미저장 — 보고서 §5.1.3 의 명시적 트레이드오프 (audit 한정 손실)</li>
 * </ul>
 *
 * <p>쿠폰 자체 정합성은 server-c 의 {@code (user_id, idempotency_key) UNIQUE} + {@code coupon.code UNIQUE}
 * 가 권위 — server-a 의 audit 손실은 발급 결과의 정합성에 영향 없음.
 */
@Service
@RequiredArgsConstructor
public class IssueRequestService {

    private static final Logger log = LoggerFactory.getLogger(IssueRequestService.class);

    private final IssueRequestRepository issueRequestRepository;
    private final EventRepository eventRepository;
    private final CouponIssuingClient couponIssuingClient;
    private final TransactionTemplate transactionTemplate;
    private final IssueRequestBatchQueue batchQueue;

    public IssueOutcome issue(IssueCommand cmd) {
        Instant now = Instant.now();

        Event event = eventRepository.findById(cmd.eventId())
            .orElseThrow(() -> new IllegalArgumentException("event not found: " + cmd.eventId()));
        if (!event.isOpenAt(now)) {
            throw new IllegalStateException("event is not open: " + cmd.eventId());
        }

        // 1) in-memory 도메인 객체 생성 (RECEIVED 상태). DB 미저장.
        IssueRequest request = IssueRequest.received(
            cmd.userId(), cmd.eventId(), cmd.idempotencyKey(), now);

        // 2) Server B 호출 — Resilience4j Circuit Breaker 가 보호.
        IssueResult result;
        try {
            result = couponIssuingClient.issue(cmd.userId(), cmd.eventId(), cmd.idempotencyKey());
        } catch (RuntimeException ex) {
            // RestClient 는 fallbackMethod 로 INTERNAL_ERROR 매핑 — 여기엔 도달하지 않음.
            // Stub 또는 다른 구현이 던질 가능성 가드.
            log.warn("coupon issuing client threw: requestId={} reason={}",
                request.getRequestId(), ex.getMessage());
            result = IssueResult.internalError("client-exception: " + ex.getClass().getSimpleName());
        }

        // 3) in-memory 상태 마감 (FORWARDED → SUCCEEDED / FAILED).
        request.markForwarded();
        if (result.isSuccess()) {
            request.markSucceeded(result.couponCode());
        } else {
            request.markFailed(result.failureReason());
        }

        // 4) batch queue 에 enqueue. 큐 가득 차면 동기 INSERT fallback (방어선 2).
        if (!batchQueue.tryEnqueue(request)) {
            // graceful degradation — 큐 포화 시 그 요청만 즉시 동기 INSERT 로 audit 보존.
            // 단, fallback save 자체가 실패해도 사용자 응답에 영향 X — server-c UNIQUE 가 쿠폰 권위
            // (보고서 §5.1.3 의 audit 한정 손실). DB 장애를 사용자 5xx 로 전파하지 않음.
            try {
                request = saveSync(request);
            } catch (RuntimeException ex) {
                log.error("sync fallback save failed — audit lost: requestId={} reason={} (see report §5.1.3)",
                    request.getRequestId(), ex.getMessage(), ex);
            }
        }

        return new IssueOutcome(request, result.status());
    }

    /**
     * 방어선 2 — 큐 가득 차면 동기 INSERT. JPA save (기존 repository) 사용 — 단건이므로
     * Hibernate 의 dirty checking + {@code @Version} 처리 OK.
     */
    private IssueRequest saveSync(IssueRequest request) {
        return Objects.requireNonNull(
            transactionTemplate.execute(status -> issueRequestRepository.save(request)),
            "fallback save returned null");
    }
}
