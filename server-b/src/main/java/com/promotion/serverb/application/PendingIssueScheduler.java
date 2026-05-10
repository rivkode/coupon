package com.promotion.serverb.application;

import com.promotion.common.coupon.CouponIssueRequestPayload;
import com.promotion.serverb.domain.IssuePendingStatus;
import com.promotion.serverb.domain.PendingIssue;
import com.promotion.serverb.infrastructure.client.UserCouponClient;
import com.promotion.serverb.infrastructure.kafka.IssueRequestPublisher;
import com.promotion.serverb.infrastructure.redis.RedisIssueRequestStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * ADR-008 보완 스케줄러. cutoff 초과 PENDING 신청에 대해 다음 순서로 처리한다:
 *
 * <ol>
 *   <li>C 의 internal GET 으로 결과 조회 → 발견되면 Redis 결과 동기화 (Mode B / C 회복)</li>
 *   <li>C 가 모르고 publishAttempts &lt; max 면 Kafka 재발행 + 카운터 INCR (Mode A 회복)</li>
 *   <li>publishAttempts 가 cap 에 도달하면 FAILED 마감</li>
 * </ol>
 *
 * <p>카운터는 publish 시도 직전에 증가시켜 영구 publish 장애에서도 cap 이 항상 수렴하게 한다 (cutoff × max
 * = 30s 안에 결론). 시도 후 publish 가 throw 하면 zset 에 그대로 두고 다음 cycle 이 재시도 — 단, 카운터는
 * 이미 증가했으므로 무한 cycle 은 방지됨.
 */
@Component
public class PendingIssueScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendingIssueScheduler.class);

    private final RedisIssueRequestStore store;
    private final UserCouponClient.Lookup lookup;
    private final IssueRequestPublisher publisher;
    private final long cutoffSeconds;
    private final int batchSize;
    private final int maxPublishAttempts;
    private final Counter republishCounter;
    private final Counter giveUpCounter;

    public PendingIssueScheduler(RedisIssueRequestStore store,
                                 UserCouponClient.Lookup lookup,
                                 IssueRequestPublisher publisher,
                                 MeterRegistry meterRegistry,
                                 @Value("${app.scheduler.pending-cutoff-seconds:10}") long cutoffSeconds,
                                 @Value("${app.scheduler.batch-size:50}") int batchSize,
                                 @Value("${app.scheduler.max-publish-attempts:3}") int maxPublishAttempts) {
        this.store = store;
        this.lookup = lookup;
        this.publisher = publisher;
        this.cutoffSeconds = cutoffSeconds;
        this.batchSize = batchSize;
        this.maxPublishAttempts = maxPublishAttempts;
        this.republishCounter = Counter.builder("pending.scheduler.republish")
                .description("ADR-008 — scheduler 의 Kafka 재발행 시도 횟수 (성공/실패 무관)")
                .register(meterRegistry);
        this.giveUpCounter = Counter.builder("pending.scheduler.give_up")
                .description("ADR-008 — publishAttempts cap 도달로 FAILED 마감한 횟수")
                .register(meterRegistry);
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
                if (result.isPresent()) {
                    UserCouponClient.Lookup.Result r = result.get();
                    IssuePendingStatus status = mapStatus(r.status());
                    store.markResult(p.userId(), p.couponTypeId(), status, r.code());
                    log.info("scheduler synced from C: userId={}, couponTypeId={}, status={}",
                            p.userId(), p.couponTypeId(), status);
                } else if (p.publishAttempts() < maxPublishAttempts) {
                    republish(p);
                } else {
                    store.markResult(p.userId(), p.couponTypeId(), IssuePendingStatus.FAILED, null);
                    giveUpCounter.increment();
                    log.info("scheduler gave up after {} publish attempts: userId={}, couponTypeId={}",
                            p.publishAttempts(), p.userId(), p.couponTypeId());
                }
            } catch (Exception ex) {
                // C lookup 자체 실패 — 다음 cycle 에 재시도. zset 그대로 둠.
                log.warn("scheduler lookup failed (will retry): userId={}, couponTypeId={}",
                        p.userId(), p.couponTypeId(), ex);
            }
        }
    }

    private void republish(PendingIssue p) {
        // 카운터/score 를 publish 직전에 갱신해 publish throw 시에도 cap 이 수렴하도록.
        Instant now = Instant.now();
        int updatedAttempts = store.recordRepublish(p.userId(), p.couponTypeId(), now);
        republishCounter.increment();
        try {
            publisher.publishForScheduler(new CouponIssueRequestPayload(
                    p.requestId(), p.userId(), p.eventId(), p.couponTypeId(), p.createdAt()));
            log.info("scheduler republished: userId={}, couponTypeId={}, attempts={}",
                    p.userId(), p.couponTypeId(), updatedAttempts);
        } catch (RuntimeException ex) {
            // 카운터/score 는 이미 갱신됨 → 다음 cycle 도 시도 가능, cap 까지만.
            log.warn("scheduler republish failed (counter already incremented to {}): userId={}, couponTypeId={}",
                    updatedAttempts, p.userId(), p.couponTypeId(), ex);
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
