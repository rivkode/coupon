package com.promotion.servera.infrastructure.batch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase C 방어선 4 — heap 사용률 모니터링 (보고서 §5.1.2).
 *
 * <p>주기적으로 JVM heap 사용률 체크 → high watermark (default 80%) 도달 시
 * batch queue 의 rejecting 모드 활성화 + 즉시 flush 강제. low watermark (70%) 회복 시 해제.
 *
 * <p>JVM 비정상 종료 (OOM kill / segfault) 자체를 막지는 못함 — 보고서 §5.1.3 의 인정된 한계.
 * 본 모니터의 목적은 OOM <b>전</b>에 신호를 잡아 batch flush 강제 + 신규 enqueue 거부 (Bulkhead 와 결합).
 *
 * <p>Bulkhead 는 별도 메커니즘 (controller 단의 admission control). 본 클래스는 큐 단계의
 * back pressure — 두 메커니즘이 서로 다른 자원을 보호.
 */
@Component
public class HeapMonitor {

    private static final Logger log = LoggerFactory.getLogger(HeapMonitor.class);

    private final IssueRequestBatchQueue queue;
    private final IssueRequestBatchFlusher flusher;
    private final BatchProperties props;

    private final Counter rejectingActivations;
    private final Counter rejectingDeactivations;

    public HeapMonitor(
        IssueRequestBatchQueue queue,
        IssueRequestBatchFlusher flusher,
        BatchProperties props,
        MeterRegistry registry
    ) {
        this.queue = queue;
        this.flusher = flusher;
        this.props = props;
        this.rejectingActivations = Counter.builder("issue_request_batch_heap_rejecting_total")
            .tag("transition", "activated")
            .description("Number of times rejecting mode was activated (heap > high watermark)")
            .register(registry);
        this.rejectingDeactivations = Counter.builder("issue_request_batch_heap_rejecting_total")
            .tag("transition", "deactivated")
            .description("Number of times rejecting mode was deactivated (heap < low watermark)")
            .register(registry);
    }

    /**
     * heap 체크. {@code app.batch.heap.check-interval-ms} 마다 실행.
     */
    @Scheduled(fixedDelayString = "${app.batch.heap.check-interval-ms}")
    public void check() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        double ratio = (double) used / max;

        boolean currentlyRejecting = queue.isRejecting();
        double high = props.heap().highWatermark();
        double low = props.heap().lowWatermark();

        if (!currentlyRejecting && ratio >= high) {
            // 진입: rejecting 모드 활성화 + 강제 flush
            queue.setRejecting(true);
            rejectingActivations.increment();
            log.warn("heap usage {} >= high watermark {} — entering rejecting mode + force flush",
                String.format("%.1f%%", ratio * 100), String.format("%.1f%%", high * 100));
            try {
                flusher.flushNow();
            } catch (RuntimeException ex) {
                log.error("force flush failed during heap pressure: {}", ex.getMessage(), ex);
            }
        } else if (currentlyRejecting && ratio <= low) {
            // 탈출: rejecting 모드 해제
            queue.setRejecting(false);
            rejectingDeactivations.increment();
            log.info("heap usage {} <= low watermark {} — exiting rejecting mode",
                String.format("%.1f%%", ratio * 100), String.format("%.1f%%", low * 100));
        }
    }
}
