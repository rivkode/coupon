package com.promotion.servera.infrastructure.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase C — IssueRequest batch insert 정책.
 *
 * <p>application.yml 의 {@code app.batch.*} 와 매핑.
 *
 * <p>5층 방어 (보고서 §5.1.2):
 * <ul>
 *   <li>방어선 1: bounded queue (capacity)</li>
 *   <li>방어선 2: 동기 INSERT fallback (queue.offer()=false 시)</li>
 *   <li>방어선 3: graceful shutdown (@PreDestroy flushNow)</li>
 *   <li>방어선 4: heap 모니터링 (high/low watermark)</li>
 *   <li>방어선 5: persistent queue — 운영 진화 영역 (미적용)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.batch")
public record BatchProperties(
    Queue queue,
    Flush flush,
    Heap heap
) {

    /**
     * watermark 운영 실수 (low > high) 시 oscillation 방지 — 부팅 시점에 fail-fast.
     */
    public BatchProperties {
        if (heap != null && heap.lowWatermark() > heap.highWatermark()) {
            throw new IllegalArgumentException(
                "lowWatermark must be <= highWatermark: low=%s high=%s"
                    .formatted(heap.lowWatermark(), heap.highWatermark()));
        }
    }

    public record Queue(int capacity) {}

    public record Flush(long intervalMs, int maxSize) {}

    public record Heap(double highWatermark, double lowWatermark, long checkIntervalMs) {}
}
