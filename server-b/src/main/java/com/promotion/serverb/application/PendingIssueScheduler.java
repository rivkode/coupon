package com.promotion.serverb.application;

import com.promotion.serverb.domain.IssuePendingStatus;
import com.promotion.serverb.domain.PendingIssue;
import com.promotion.serverb.infrastructure.client.UserCouponClient;
import com.promotion.serverb.infrastructure.redis.RedisIssueRequestStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * ADR-008 보완 스케줄러. 10 초 이상 PENDING 인 신청에 대해 C 의 internal GET 호출 → 결과 반영.
 *
 * <p>Kafka publish 실패 / consumer lag / publish 유실 모두를 한 번에 막는 안전망.
 */
@Component
public class PendingIssueScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendingIssueScheduler.class);

    private final RedisIssueRequestStore store;
    private final UserCouponClient.Lookup lookup;
    private final long cutoffSeconds;
    private final int batchSize;

    public PendingIssueScheduler(RedisIssueRequestStore store,
                                 UserCouponClient.Lookup lookup,
                                 @Value("${app.scheduler.pending-cutoff-seconds:10}") long cutoffSeconds,
                                 @Value("${app.scheduler.batch-size:50}") int batchSize) {
        this.store = store;
        this.lookup = lookup;
        this.cutoffSeconds = cutoffSeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.fixed-delay-ms:1000}")
    public void run() {
        long cutoffMs = Instant.now().minusSeconds(cutoffSeconds).toEpochMilli();
        List<PendingIssue> stale = store.findPendingOlderThan(cutoffMs, batchSize);
        if (stale.isEmpty()) {
            return;
        }
        for (PendingIssue p : stale) {
            try {
                Optional<UserCouponClient.Lookup.Result> result = lookup.findOne(p.userId(), p.couponTypeId());
                if (result.isEmpty()) {
                    // C 에 아무것도 없음 — Kafka 로 안 갔거나 처리 실패. FAILED 로 마감.
                    store.markResult(p.userId(), p.couponTypeId(), IssuePendingStatus.FAILED, null);
                    log.info("scheduler marked FAILED (not found in C): userId={}, couponTypeId={}",
                            p.userId(), p.couponTypeId());
                } else {
                    UserCouponClient.Lookup.Result r = result.get();
                    IssuePendingStatus status = mapStatus(r.status());
                    store.markResult(p.userId(), p.couponTypeId(), status, r.code());
                    log.info("scheduler synced from C: userId={}, couponTypeId={}, status={}",
                            p.userId(), p.couponTypeId(), status);
                }
            } catch (Exception ex) {
                // 일시 장애 — 다음 cycle 에 재시도. zset 에 그대로 둠.
                log.warn("scheduler lookup failed (will retry): userId={}, couponTypeId={}",
                        p.userId(), p.couponTypeId(), ex);
            }
        }
    }

    private static IssuePendingStatus mapStatus(String cStatus) {
        if (cStatus == null) return IssuePendingStatus.FAILED;
        return switch (cStatus) {
            case "SUCCESS" -> IssuePendingStatus.SUCCESS;
            case "SOLD_OUT" -> IssuePendingStatus.SOLD_OUT;
            case "USED" -> IssuePendingStatus.SUCCESS;
            default -> IssuePendingStatus.FAILED;
        };
    }
}
