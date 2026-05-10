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
 *   <li>없으면 pending hash + zset 등록 (publishAttempts=1 포함)</li>
 *   <li>Kafka publish (idempotent producer + retries — ADR-008)</li>
 *   <li>ACCEPTED 응답</li>
 * </ol>
 *
 * <p>SOLD_OUT 단락은 server-a 진입에서 처리 (ADR-011). B 까지 도달한 요청은 매진 캐시가 없거나 Redis blip
 * 으로 fall-through 한 것 — 정상 흐름을 그대로 진행하면 C 가 비관적 락 + UNIQUE 로 정합성 보장.
 *
 * <p>Publish 실패는 swallow — Redis 적재가 "처리 의도 커밋" 의 진실이고, 30s 안에 스케줄러가 재발행으로
 * 회복하므로(ADR-008) 사용자에게 5xx 를 돌려 DUPLICATE 재시도 흐름을 만드는 것보다 ACCEPTED 응답이
 * 일관됨. 사용자는 폴링/내쿠폰 조회로 최종 결과 확인.
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
        try {
            publisher.publish(new CouponIssueRequestPayload(requestId, userId, eventId, couponTypeId, now));
        } catch (RuntimeException ex) {
            // Redis 는 attempts=1 로 적재됨 → 10s 후 스케줄러가 picks → 재발행. cap 안에서 회복.
            log.warn("initial publish failed — scheduler will retry within SLA: requestId={}, userId={}, couponTypeId={}",
                    requestId, userId, couponTypeId, ex);
        }
        log.info("accepted: requestId={}, userId={}, couponTypeId={}", requestId, userId, couponTypeId);
        return IssueAcceptanceResult.accepted(requestId);
    }
}
