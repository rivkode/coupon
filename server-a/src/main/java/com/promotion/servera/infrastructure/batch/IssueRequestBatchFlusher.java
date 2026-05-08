package com.promotion.servera.infrastructure.batch;

import com.promotion.servera.domain.IssueRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase C — IssueRequest 의 batch INSERT (보고서 §5.1).
 *
 * <p><b>흐름</b>: {@link IssueRequestBatchQueue} 에서 N 개 drain → {@link JdbcTemplate#batchUpdate} 로 1 commit.
 *
 * <p><b>방어선 3 (graceful shutdown)</b>: {@link PreDestroy} 에서 큐를 모두 drain 후 종료.
 * SIGTERM 받은 경우 (배포 / scale-in) — 30초 grace period 안에 완료.
 *
 * <p>JdbcTemplate raw INSERT 사용 사유: JPA save 1건씩 호출하면 Hibernate 의 dirty checking +
 * {@code @Version} 처리가 추가 round-trip. batch 의 의미가 약화. Raw SQL 로 한 번에 INSERT.
 *
 * <p>본 시점의 INSERT 는 FINAL state 만 (RECEIVED 중간 상태 미저장). 보고서 §5.1.3 의 트레이드오프 명시.
 */
@Component
public class IssueRequestBatchFlusher {

    private static final Logger log = LoggerFactory.getLogger(IssueRequestBatchFlusher.class);

    private static final String INSERT_SQL = """
        INSERT INTO issue_request
            (request_id, user_id, event_id, idempotency_key, status, coupon_code, failure_reason,
             version, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
        """;

    private final IssueRequestBatchQueue queue;
    private final JdbcTemplate jdbcTemplate;
    private final BatchProperties props;
    private final Timer flushTimer;
    private final Counter rowsInsertedCounter;
    private final Counter flushFailureCounter;

    public IssueRequestBatchFlusher(
        IssueRequestBatchQueue queue,
        JdbcTemplate jdbcTemplate,
        BatchProperties props,
        MeterRegistry registry
    ) {
        this.queue = queue;
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        this.flushTimer = Timer.builder("issue_request_batch_flush_duration_seconds")
            .description("Wall-clock time per batch flush")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
        this.rowsInsertedCounter = Counter.builder("issue_request_batch_rows_inserted_total")
            .description("Total rows inserted via batch flush")
            .register(registry);
        this.flushFailureCounter = Counter.builder("issue_request_batch_flush_failures_total")
            .description("Number of batch flush attempts that threw")
            .register(registry);
    }

    /**
     * 주기적 flush. {@code app.batch.flush.interval-ms} 마다 큐를 drain.
     * 큐가 비어있으면 즉시 반환 (NO-OP). 실패해도 다음 cycle 에서 재시도.
     */
    @Scheduled(fixedDelayString = "${app.batch.flush.interval-ms}")
    public void scheduledFlush() {
        flushOnce();
    }

    /** HeapMonitor 의 강제 flush 진입점 (방어선 4). */
    public void flushNow() {
        flushOnce();
    }

    /**
     * 한 cycle 의 flush. 큐를 max-size 까지 drain → batchUpdate.
     */
    private void flushOnce() {
        List<IssueRequest> batch = queue.drain(props.flush().maxSize());
        if (batch.isEmpty()) {
            return;
        }

        Timer.Sample sample = Timer.start();
        try {
            doBatchInsert(batch);
            rowsInsertedCounter.increment(batch.size());
            if (log.isDebugEnabled()) {
                log.debug("batch flushed: rows={} queueSize={}", batch.size(), queue.size());
            }
        } catch (RuntimeException ex) {
            // DataAccessException + setValues 단계 NPE 등 모든 RuntimeException 흡수.
            // scheduled task 자체가 죽지 않도록 폭 넓게 catch — 그 batch 의 audit 만 손실.
            // 보고서 §5.1.3 트레이드오프 (audit 한정 손실). flush_failures_total metric 이 운영 알림 신호.
            flushFailureCounter.increment();
            log.error("batch flush failed: rows={} reason={} (audit lost — see report §5.1.3)",
                batch.size(), ex.getMessage(), ex);
        } finally {
            sample.stop(flushTimer);
        }
    }

    private void doBatchInsert(List<IssueRequest> batch) {
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                IssueRequest r = batch.get(i);
                ps.setString(1, r.getRequestId());
                ps.setLong(2, r.getUserId());
                ps.setLong(3, r.getEventId());
                ps.setString(4, r.getIdempotencyKey());
                ps.setString(5, r.getStatus().name());
                ps.setString(6, r.getCouponCode() == null ? null : r.getCouponCode().value());
                ps.setString(7, r.getFailureReason());
                ps.setTimestamp(8, Timestamp.from(r.getCreatedAt()));
                ps.setTimestamp(9, Timestamp.from(r.getUpdatedAt()));
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
    }

    /**
     * 방어선 3 — graceful shutdown.
     * SIGTERM 수신 시 Spring 이 본 메서드 호출 → 큐 안의 모든 데이터 즉시 flush.
     * 비정상 종료 (kill -9 / OOM) 시는 동작하지 않음 — 보고서 §5.1.3 트레이드오프.
     *
     * <p>terminate 보장: iteration 상한 + wall-clock deadline (20s, K8s default 30s grace 안에 완료).
     * 동시 enqueue 가 진행 중이어도 (in-flight 요청의 가상 스레드 등) 두 상한 중 먼저 도달 시 종료.
     * 잔여 데이터는 명시 로깅 — 보고서 §5.1.3 의 audit 손실 윈도우와 정합.
     */
    @PreDestroy
    public void onShutdown() {
        int initial = queue.size();
        if (initial == 0) {
            return;
        }
        log.info("Draining IssueRequest batch queue before shutdown (size={})", initial);

        // 새 enqueue 차단 — in-flight 요청의 fallback 경로가 sync save 로 가도록 강제.
        queue.setRejecting(true);

        int maxIterations = (initial / props.flush().maxSize()) + 2;
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        for (int i = 0; i < maxIterations && System.nanoTime() < deadlineNanos; i++) {
            if (queue.size() == 0) {
                break;
            }
            try {
                flushOnce();
            } catch (RuntimeException ex) {
                log.error("error during shutdown drain (iter={}): {}", i, ex.getMessage(), ex);
                break;
            }
        }

        int leftover = queue.size();
        if (leftover > 0) {
            log.warn("shutdown drain incomplete — leftover audit rows lost: {} (see report §5.1.3)", leftover);
        } else {
            log.info("IssueRequest batch queue drain complete (drained={})", initial);
        }
    }
}
