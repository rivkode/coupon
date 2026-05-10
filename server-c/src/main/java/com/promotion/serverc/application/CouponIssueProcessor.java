package com.promotion.serverc.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.common.coupon.CouponCode;
import com.promotion.common.coupon.CouponIssueRequestPayload;
import com.promotion.common.coupon.CouponIssueResultPayload;
import com.promotion.common.coupon.CouponIssueResultStatus;
import com.promotion.serverc.domain.UserCouponStatus;
import com.promotion.serverc.infrastructure.persistence.CouponTypeInventoryJpaEntity;
import com.promotion.serverc.infrastructure.persistence.CouponTypeInventoryJpaRepository;
import com.promotion.serverc.infrastructure.persistence.EventJpaEntity;
import com.promotion.serverc.infrastructure.persistence.EventJpaRepository;
import com.promotion.serverc.infrastructure.persistence.OutboxEventJpaEntity;
import com.promotion.serverc.infrastructure.persistence.OutboxEventJpaRepository;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaEntity;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaRepository;
import com.promotion.serverc.infrastructure.redis.CouponAvailabilityCache;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Kafka consumer 가 호출하는 발급 처리 트랜잭션 (CLAUDE.md ADR-002 / ADR-003).
 *
 * <p>1 트랜잭션:
 * <ol>
 *   <li>(user_id, coupon_type_id) 중복 체크 (UNIQUE 가 권위 — 본 체크는 빠른 단락)</li>
 *   <li>event 유효성 (started_at ≤ now ≤ ended_at)</li>
 *   <li>coupon_type_inventory 비관적 락 차감 (SELECT ... FOR UPDATE)</li>
 *   <li>user_coupon INSERT (SUCCESS / SOLD_OUT)</li>
 *   <li>outbox_event INSERT (결과 publish 용)</li>
 * </ol>
 *
 * <p>Kafka publish 는 트랜잭션 밖 — OutboxPoller 가 별도 처리.
 */
@Service
@RequiredArgsConstructor
public class CouponIssueProcessor {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueProcessor.class);
    private static final String EVENT_TYPE_ISSUE_RESULT = "ISSUE_RESULT";

    private final UserCouponJpaRepository userCouponRepository;
    private final CouponTypeInventoryJpaRepository inventoryRepository;
    private final EventJpaRepository eventRepository;
    private final OutboxEventJpaRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final CouponAvailabilityCache availabilityCache;

    @Transactional
    public void process(CouponIssueRequestPayload request) {
        if (userCouponRepository.existsByUserIdAndCouponTypeId(request.userId(), request.couponTypeId())) {
            log.info("duplicate request — already issued: userId={}, couponTypeId={}",
                    request.userId(), request.couponTypeId());
            return;
        }

        EventJpaEntity event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> new IllegalStateException("event not found: " + request.eventId()));

        LocalDateTime now = LocalDateTime.now();
        if (!event.isActive(now)) {
            saveResult(request, UserCouponStatus.FAILED, null, now);
            saveOutbox(toResultPayload(request, CouponIssueResultStatus.FAILED, null, now));
            log.info("event not active: eventId={}, userId={}", request.eventId(), request.userId());
            return;
        }

        CouponTypeInventoryJpaEntity inventory = inventoryRepository
                .findForUpdate(request.eventId(), request.couponTypeId())
                .orElseThrow(() -> new IllegalStateException(
                        "inventory not found: eventId=" + request.eventId() + ", couponTypeId=" + request.couponTypeId()));

        if (!inventory.decrement()) {
            saveResult(request, UserCouponStatus.SOLD_OUT, null, now);
            saveOutbox(toResultPayload(request, CouponIssueResultStatus.SOLD_OUT, null, now));
            registerAvailabilityCheck(request.eventId(), request.couponTypeId());
            log.info("sold out: eventId={}, couponTypeId={}", request.eventId(), request.couponTypeId());
            return;
        }

        String code = CouponCode.generate().value();
        try {
            saveResult(request, UserCouponStatus.SUCCESS, code, now);
        } catch (DataIntegrityViolationException dup) {
            log.info("unique violation on user_coupon insert (idempotent): userId={}, couponTypeId={}",
                    request.userId(), request.couponTypeId());
            return;
        }
        saveOutbox(toResultPayload(request, CouponIssueResultStatus.SUCCESS, code, now));
        registerAvailabilityCheck(request.eventId(), request.couponTypeId());
        log.info("issued: code={}, userId={}, couponTypeId={}", code, request.userId(), request.couponTypeId());
    }

    /**
     * ADR-011 — 트랜잭션 커밋 직후 재고를 fresh read 해서 0 이면 negative cache 적재.
     * 트랜잭션 안에서 결정하지 않는 이유: 롤백 시 ghost cache write 방지 + 동시 다른 tx 가
     * 더 진행시킨 최종 상태를 반영. cache write 자체는 best-effort (캐시는 권위 아님).
     */
    private void registerAvailabilityCheck(long eventId, long couponTypeId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    inventoryRepository.findByEventIdAndCouponTypeId(eventId, couponTypeId)
                            .filter(inv -> inv.getAvailableCount() == 0)
                            .ifPresent(inv -> availabilityCache.markSoldOut(eventId, couponTypeId));
                } catch (Exception ex) {
                    // MySQL hiccup / 커넥션 풀 고갈 등 — silent fail 방지. cache 누락은 다음 SOLD_OUT 호출이 회복.
                    log.warn("availability post-check failed (cache write skipped): eventId={}, couponTypeId={} reason={}",
                            eventId, couponTypeId, ex.getMessage());
                }
            }
        });
    }

    private void saveResult(CouponIssueRequestPayload request, UserCouponStatus status,
                            String code, LocalDateTime issuedAt) {
        userCouponRepository.save(new UserCouponJpaEntity(
                code != null ? code : generatePlaceholderCode(request),
                request.userId(),
                request.eventId(),
                request.couponTypeId(),
                status,
                issuedAt
        ));
    }

    /** SOLD_OUT/FAILED 시에도 user_coupon row 를 남겨 사용자 폴링 응답이 가능하도록. code 는 placeholder. */
    private String generatePlaceholderCode(CouponIssueRequestPayload request) {
        return "X" + request.requestId().substring(0, Math.min(11, request.requestId().length()));
    }

    private CouponIssueResultPayload toResultPayload(CouponIssueRequestPayload request,
                                                    CouponIssueResultStatus status,
                                                    String code,
                                                    LocalDateTime processedAt) {
        return new CouponIssueResultPayload(
                request.requestId(),
                request.userId(),
                request.eventId(),
                request.couponTypeId(),
                status,
                code,
                processedAt.toInstant(ZoneOffset.UTC)
        );
    }

    private void saveOutbox(CouponIssueResultPayload payload) {
        try {
            outboxRepository.save(new OutboxEventJpaEntity(
                    payload.requestId(),
                    EVENT_TYPE_ISSUE_RESULT,
                    objectMapper.writeValueAsString(payload)
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
    }
}
