package com.promotion.serverb.application;

import com.promotion.common.coupon.CouponIssueRequestPayload;
import com.promotion.common.coupon.IssueAcceptanceResult;
import com.promotion.serverb.infrastructure.kafka.IssueRequestPublisher;
import com.promotion.serverb.infrastructure.redis.RedisIssueRequestStore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * 발급 신청 접수 (CLAUDE.md ADR-001).
 *
 * <ol>
 *   <li>Redis EXISTS 로 (user, couponType) 중복 체크 → 있으면 DUPLICATE</li>
 *   <li>없으면 pending hash + zset 등록</li>
 *   <li>Kafka publish (idempotent producer + retries — ADR-008)</li>
 *   <li>ACCEPTED 응답</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class CouponIssueAcceptService {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueAcceptService.class);

    private final RedisIssueRequestStore store;
    private final IssueRequestPublisher publisher;

    public IssueAcceptanceResult accept(long userId, long eventId, long couponTypeId) {
        String requestId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        boolean firstWrite = store.savePendingIfAbsent(requestId, userId, eventId, couponTypeId, now);
        if (!firstWrite) {
            log.info("duplicate request: userId={}, couponTypeId={}", userId, couponTypeId);
            return IssueAcceptanceResult.duplicate(requestId);
        }
        publisher.publish(new CouponIssueRequestPayload(requestId, userId, eventId, couponTypeId, now));
        log.info("accepted: requestId={}, userId={}, couponTypeId={}", requestId, userId, couponTypeId);
        return IssueAcceptanceResult.accepted(requestId);
    }
}
