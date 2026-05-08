package com.promotion.servera.infrastructure.batch;

import com.promotion.servera.domain.IssueRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * IssueRequest 의 in-memory batch queue (Phase C, 보고서 §5.1).
 *
 * <p><b>방어선 1</b>: {@link ArrayBlockingQueue} capacity 상한 (default 1,000).
 * 무한 증가를 차단해 OOM 위험을 1차 방어.
 *
 * <p><b>방어선 4 와 협업</b>: {@code rejecting} flag 가 true 면 {@link #tryEnqueue} 가
 * 즉시 false 반환 — heap 압박 시 신규 enqueue 거부 (HeapMonitor 가 토글).
 *
 * <p>thread-safe — 여러 가상 스레드 동시 enqueue + 단일 flusher thread 의 drain 동시 발생.
 */
@Component
public class IssueRequestBatchQueue {

    private final ArrayBlockingQueue<IssueRequest> queue;
    private final AtomicBoolean rejecting = new AtomicBoolean(false);

    private final Counter enqueueSuccessCounter;
    private final Counter enqueueQueueFullCounter;  // tag=queue_full — 방어선 2 fallback 발동 빈도
    private final Counter enqueueRejectingCounter;  // tag=rejecting_mode — 방어선 4 발동 시 거부 빈도

    public IssueRequestBatchQueue(BatchProperties props, MeterRegistry registry) {
        this.queue = new ArrayBlockingQueue<>(props.queue().capacity());

        // 현재 큐 깊이 — Grafana 패널 (보고서 §5.1.4)
        Gauge.builder("issue_request_batch_queue_size", queue, ArrayBlockingQueue::size)
            .description("Current depth of in-memory batch insert queue")
            .register(registry);

        this.enqueueSuccessCounter = Counter.builder("issue_request_batch_enqueue_total")
            .tag("result", "success")
            .description("Successful enqueues into the batch queue")
            .register(registry);
        this.enqueueQueueFullCounter = Counter.builder("issue_request_batch_enqueue_total")
            .tag("result", "queue_full")
            .description("Rejected enqueues — queue at capacity (방어선 2 fallback 발동)")
            .register(registry);
        this.enqueueRejectingCounter = Counter.builder("issue_request_batch_enqueue_total")
            .tag("result", "rejecting_mode")
            .description("Rejected enqueues — heap pressure rejecting mode (방어선 4)")
            .register(registry);
    }

    /**
     * 큐에 적재 시도. 실패 시 {@code false} 반환 → 호출자가 동기 fallback 발동 (방어선 2).
     */
    public boolean tryEnqueue(IssueRequest request) {
        if (rejecting.get()) {
            enqueueRejectingCounter.increment();
            return false;
        }
        if (queue.offer(request)) {
            enqueueSuccessCounter.increment();
            return true;
        }
        enqueueQueueFullCounter.increment();
        return false;
    }

    /**
     * 최대 {@code maxSize} 개 drain. flusher 에서만 호출.
     */
    public List<IssueRequest> drain(int maxSize) {
        List<IssueRequest> out = new ArrayList<>(Math.min(maxSize, queue.size()));
        queue.drainTo(out, maxSize);
        return out;
    }

    public int size() {
        return queue.size();
    }

    /** 방어선 4 — HeapMonitor 가 호출. */
    public void setRejecting(boolean reject) {
        rejecting.set(reject);
    }

    public boolean isRejecting() {
        return rejecting.get();
    }
}
