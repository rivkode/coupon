package com.promotion.servera.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.promotion.servera.domain.IssueRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 방어선 1 (bounded queue) + 방어선 4 (rejecting flag) 단위 테스트.
 */
class IssueRequestBatchQueueTest {

    private static final BatchProperties props(int capacity) {
        return new BatchProperties(
            new BatchProperties.Queue(capacity),
            new BatchProperties.Flush(200, 100),
            new BatchProperties.Heap(0.80, 0.70, 5000));
    }

    private IssueRequest sampleRequest(long userId) {
        return IssueRequest.received(userId, 1L, "idem-" + userId, Instant.parse("2026-05-08T00:00:00Z"));
    }

    @Test
    @DisplayName("정상 enqueue — drainTo 로 모두 회수 가능")
    void enqueue_and_drain() {
        MeterRegistry registry = new SimpleMeterRegistry();
        IssueRequestBatchQueue queue = new IssueRequestBatchQueue(props(1000), registry);

        for (long i = 0; i < 5; i++) {
            assertThat(queue.tryEnqueue(sampleRequest(i))).isTrue();
        }
        assertThat(queue.size()).isEqualTo(5);

        List<IssueRequest> drained = queue.drain(100);
        assertThat(drained).hasSize(5);
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("방어선 1 — capacity 도달 시 tryEnqueue=false")
    void capacity_full_returns_false() {
        MeterRegistry registry = new SimpleMeterRegistry();
        IssueRequestBatchQueue queue = new IssueRequestBatchQueue(props(3), registry);

        assertThat(queue.tryEnqueue(sampleRequest(1))).isTrue();
        assertThat(queue.tryEnqueue(sampleRequest(2))).isTrue();
        assertThat(queue.tryEnqueue(sampleRequest(3))).isTrue();
        // 4번째는 거부.
        assertThat(queue.tryEnqueue(sampleRequest(4))).isFalse();

        // queue_full 카운터 증가.
        Counter qFull = registry.find("issue_request_batch_enqueue_total")
            .tag("result", "queue_full").counter();
        assertThat(qFull).isNotNull();
        assertThat(qFull.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("방어선 4 — rejecting=true 면 즉시 false (capacity 와 무관)")
    void rejecting_mode_drops_immediately() {
        MeterRegistry registry = new SimpleMeterRegistry();
        IssueRequestBatchQueue queue = new IssueRequestBatchQueue(props(1000), registry);

        queue.setRejecting(true);

        assertThat(queue.tryEnqueue(sampleRequest(1))).isFalse();
        assertThat(queue.size()).isZero();

        // rejecting_mode 카운터 증가.
        Counter rejecting = registry.find("issue_request_batch_enqueue_total")
            .tag("result", "rejecting_mode").counter();
        assertThat(rejecting).isNotNull();
        assertThat(rejecting.count()).isEqualTo(1.0);

        // 해제 시 다시 enqueue 가능.
        queue.setRejecting(false);
        assertThat(queue.tryEnqueue(sampleRequest(2))).isTrue();
    }

    @Test
    @DisplayName("drain — 큐가 비어있으면 빈 리스트 반환")
    void drain_empty_returns_empty_list() {
        MeterRegistry registry = new SimpleMeterRegistry();
        IssueRequestBatchQueue queue = new IssueRequestBatchQueue(props(100), registry);

        List<IssueRequest> drained = queue.drain(50);
        assertThat(drained).isEmpty();
    }

    @Test
    @DisplayName("drain — maxSize 만큼만 회수 (큐에 더 많이 있어도)")
    void drain_respects_max_size() {
        MeterRegistry registry = new SimpleMeterRegistry();
        IssueRequestBatchQueue queue = new IssueRequestBatchQueue(props(100), registry);

        for (long i = 0; i < 10; i++) {
            queue.tryEnqueue(sampleRequest(i));
        }

        List<IssueRequest> drained = queue.drain(3);
        assertThat(drained).hasSize(3);
        assertThat(queue.size()).isEqualTo(7);
    }
}
