package com.promotion.servera.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HeapMonitor 의 상태 전이만 검증 — 실제 heap 사용률은 Runtime 의존이라 OS/JVM 통제 어려움.
 * IssueRequestBatchQueue 의 rejecting flag 가 의도대로 토글되는지에 집중.
 *
 * <p>실제 heap 측정 동작은 통합/부하 환경에서 검증.
 */
class HeapMonitorTest {

    private static BatchProperties props(double high, double low) {
        return new BatchProperties(
            new BatchProperties.Queue(1000),
            new BatchProperties.Flush(200, 100),
            new BatchProperties.Heap(high, low, 5000));
    }

    @Test
    @DisplayName("rejecting=false 상태에서 high watermark 는 항상 도달 못 한 상태일 수 있음 — 호출만 확인")
    void check_does_not_throw() {
        MeterRegistry registry = new SimpleMeterRegistry();
        IssueRequestBatchQueue queue = new IssueRequestBatchQueue(props(0.99, 0.50), registry);
        IssueRequestBatchFlusher flusher = mock(IssueRequestBatchFlusher.class);
        HeapMonitor monitor = new HeapMonitor(queue, flusher, props(0.99, 0.50), registry);

        // 99% 임계 → 일반 테스트 환경에서는 도달 어려움. 호출이 throw 없이 끝나는지만 확인.
        monitor.check();

        // 평소 환경의 heap 은 < 99% 가정 — flush 미호출 / rejecting 미활성.
        assertThat(queue.isRejecting()).isFalse();
        verify(flusher, never()).flushNow();
    }

    @Test
    @DisplayName("low watermark 가 high 와 같음 + rejecting=true 시작 → check 후 해제")
    void deactivation_path() {
        // 강제로 rejecting=true 진입 후 heap < low 이면 해제.
        // low=high=0.99 로 설정 (invariant 충족) — 평소 ratio < 99% 이므로 deactivation 발동.
        MeterRegistry registry = new SimpleMeterRegistry();
        IssueRequestBatchQueue queue = new IssueRequestBatchQueue(props(0.99, 0.99), registry);
        IssueRequestBatchFlusher flusher = mock(IssueRequestBatchFlusher.class);
        HeapMonitor monitor = new HeapMonitor(queue, flusher, props(0.99, 0.99), registry);

        queue.setRejecting(true);
        assertThat(queue.isRejecting()).isTrue();

        monitor.check();

        // 평소 환경 ratio < 0.99 → 해제됨.
        assertThat(queue.isRejecting()).isFalse();
    }

    @Test
    @DisplayName("rejecting=false + ratio < high → 변화 없음 (전이 조건 unmet)")
    void no_transition_when_below_high() {
        MeterRegistry registry = new SimpleMeterRegistry();
        IssueRequestBatchQueue queue = new IssueRequestBatchQueue(props(0.99, 0.50), registry);
        IssueRequestBatchFlusher flusher = mock(IssueRequestBatchFlusher.class);
        HeapMonitor monitor = new HeapMonitor(queue, flusher, props(0.99, 0.50), registry);

        // high=0.99 도달 어려움 + rejecting=false 시작 → 변화 없음.
        monitor.check();

        assertThat(queue.isRejecting()).isFalse();
        verify(flusher, never()).flushNow();
    }
}
