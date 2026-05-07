package com.promotion.serverb.application;

import com.promotion.serverb.domain.CouponIssueOutbox;
import com.promotion.serverb.domain.CouponIssueOutboxRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox poller (CLAUDE.md ADR-002).
 *
 * <p><b>Cycle</b>:
 * <ol>
 *   <li>{@code findUnpublishedForUpdate(BATCH)} — published=false 행을 SKIP LOCKED 로 batch 조회 (트랜잭션 1)</li>
 *   <li>각 행을 Kafka 로 동기 발행 (트랜잭션 밖)</li>
 *   <li>발행 성공 시 markPublished + save (트랜잭션 N — 행별 분리, 부분 진행 허용)</li>
 *   <li>발행 실패 catch → 로그 + 다음 cycle 재시도 (markPublished 호출 안 함, at-least-once)</li>
 * </ol>
 *
 * <p><b>트랜잭션 경계</b>: Kafka send 는 트랜잭션 밖. CLAUDE.md §10 안티패턴
 * ("@Transactional 안에서 외부 호출") 회피.
 *
 * <p><b>SKIP LOCKED 의 한계</b> — Step 1 트랜잭션이 commit 되면 row lock 도 해제. publish 와
 * markPublished 사이에 다른 인스턴스가 같은 행을 다시 SELECT 가능 → 중복 발행 가능.
 * 즉 SKIP LOCKED 는 "동시 SELECT 끼리의 직렬화" 정도만 보장하고, **진짜 동시 발행 방어는
 * Server C 의 UNIQUE constraint** 이 권위 (PR #15). 본 과제는 단일 인스턴스 운영을 가정하고,
 * end-to-end exactly-once 는 Consumer 의 UNIQUE 가 권위 — README / docs/decisions 명시.
 *
 * <p><b>발행 실패 분리</b>: batch 안 1건 실패가 다른 건 발행을 막지 않도록 행별 try/catch.
 *
 * <p><b>스케줄 동시 실행 방지</b>: Spring 의 {@code @Scheduled} 는 같은 메서드의 두 cycle 이
 * 동시에 돌지 않도록 단일 thread executor 로 실행 (Spring Boot 기본). 별도 lock 불필요.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final CouponIssueOutboxRepository outboxRepository;
    private final CouponIssuedEventPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;

    public OutboxPoller(
        CouponIssueOutboxRepository outboxRepository,
        CouponIssuedEventPublisher publisher,
        TransactionTemplate transactionTemplate,
        @Value("${app.outbox.poller.batch-size}") int batchSize
    ) {
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
    }

    @Scheduled(
        fixedDelayString = "${app.outbox.poller.fixed-delay-ms}",
        // 운영 default 5000ms (warmup 후 첫 cycle). IT 에서 이 값을 크게 주어 자동 cycle 비활성화.
        initialDelayString = "${app.outbox.poller.initial-delay-ms:5000}"
    )
    public void poll() {
        try {
            int published = pollOnce();
            if (published > 0 && log.isDebugEnabled()) {
                log.debug("outbox poller published rows: count={}", published);
            }
        } catch (Exception e) {
            // schedule thread 가 죽어 다음 cycle 이 멈추는 것을 방지. 다음 cycle 에서 재시도.
            log.error("outbox poller cycle failed unexpectedly", e);
        }
    }

    /**
     * 1 cycle 실행. 테스트가 호출 가능 (반환값 = 성공한 행 수).
     */
    public int pollOnce() {
        List<CouponIssueOutbox> batch = transactionTemplate.execute(
            status -> outboxRepository.findUnpublishedForUpdate(batchSize));
        if (batch == null || batch.isEmpty()) {
            return 0;
        }

        int success = 0;
        for (CouponIssueOutbox outbox : batch) {
            if (publishAndMark(outbox)) {
                success++;
            }
        }
        return success;
    }

    private boolean publishAndMark(CouponIssueOutbox outbox) {
        try {
            publisher.publish(outbox);
        } catch (CouponIssuedEventPublishException e) {
            log.error(
                "outbox publish failed; will retry next cycle: couponCode={} idem={} userId={}",
                outbox.couponCode().value(),
                outbox.idempotencyKey(),
                outbox.getEvent().userId(),
                e);
            return false;
        } catch (RuntimeException e) {
            // 직렬화 결함 등 예기치 못한 예외도 batch 안 다른 행 발행을 막지 않도록 흡수.
            log.error(
                "outbox publish unexpected error; skipping row, retry next cycle: couponCode={} idem={}",
                outbox.couponCode().value(),
                outbox.idempotencyKey(),
                e);
            return false;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                outbox.markPublished(Instant.now());
                outboxRepository.save(outbox);
            });
            return true;
        } catch (RuntimeException e) {
            // markPublished 실패 = 이미 published(다른 instance가 처리) 또는 DB 오류.
            // Kafka 는 이미 발행됨 → 다음 cycle 의 SELECT 에서도 published=true 면 미선택.
            // published=false 인 채로 남으면 다음 cycle 재발행 → at-least-once.
            log.error(
                "outbox markPublished failed after kafka send; coupon may be re-published next cycle: couponCode={} idem={}",
                outbox.couponCode().value(),
                outbox.idempotencyKey(),
                e);
            return false;
        }
    }
}
