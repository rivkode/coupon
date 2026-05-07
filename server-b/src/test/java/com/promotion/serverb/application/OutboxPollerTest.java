package com.promotion.serverb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.promotion.common.coupon.CouponCode;
import com.promotion.serverb.domain.CouponIssueOutbox;
import com.promotion.serverb.domain.CouponIssueOutboxRepository;
import com.promotion.serverb.domain.CouponIssuedEvent;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxPollerTest {

    private CouponIssueOutboxRepository outboxRepository;
    private CouponIssuedEventPublisher publisher;
    private TransactionTemplate transactionTemplate;
    private OutboxPoller poller;

    @BeforeEach
    void setUp() {
        outboxRepository = Mockito.mock(CouponIssueOutboxRepository.class);
        publisher = Mockito.mock(CouponIssuedEventPublisher.class);
        transactionTemplate = Mockito.mock(TransactionTemplate.class);

        // execute(TransactionCallback) 가 callback 을 그대로 실행 (Repository 호출 결과 반환).
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any(TransactionCallback.class));

        // executeWithoutResult — markPublished + save 의 트랜잭션. 그대로 실행.
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = inv.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        poller = new OutboxPoller(outboxRepository, publisher, transactionTemplate, 100);
    }

    @Test
    @DisplayName("미발행 행 없음 — publish / save 호출 없음, 0 반환")
    void empty_batch_does_nothing() {
        when(outboxRepository.findUnpublishedForUpdate(anyInt())).thenReturn(List.of());

        int result = poller.pollOnce();

        assertThat(result).isZero();
        verify(publisher, never()).publish(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("정상 발행 — publish + markPublished + save 호출, 반환값 = batch size")
    void publishes_and_marks() {
        CouponIssueOutbox o1 = newOutbox(1L, "code-1", "idem-1");
        CouponIssueOutbox o2 = newOutbox(2L, "code-2", "idem-2");
        when(outboxRepository.findUnpublishedForUpdate(100)).thenReturn(List.of(o1, o2));
        // publisher.publish 는 default no-op (Mockito void 메서드 default 동작).

        int result = poller.pollOnce();

        assertThat(result).isEqualTo(2);
        verify(publisher, times(1)).publish(o1);
        verify(publisher, times(1)).publish(o2);
        verify(outboxRepository, times(2)).save(any(CouponIssueOutbox.class));
        assertThat(o1.isPublished()).isTrue();
        assertThat(o2.isPublished()).isTrue();
    }

    @Test
    @DisplayName("일부 발행 실패 — 실패 행은 markPublished 안 됨, 성공 행만 카운트")
    void publish_failure_skips_mark() {
        CouponIssueOutbox o1 = newOutbox(1L, "code-1", "idem-1");
        CouponIssueOutbox o2 = newOutbox(2L, "code-2", "idem-2");
        when(outboxRepository.findUnpublishedForUpdate(100)).thenReturn(List.of(o1, o2));
        doThrow(new CouponIssuedEventPublishException("simulated", new RuntimeException()))
            .when(publisher).publish(o1);
        doNothing().when(publisher).publish(o2);

        int result = poller.pollOnce();

        assertThat(result).isEqualTo(1);
        verify(outboxRepository, times(1)).save(o2);
        assertThat(o1.isPublished()).isFalse();
        assertThat(o2.isPublished()).isTrue();
    }

    @Test
    @DisplayName("publish 성공 후 markPublished 트랜잭션 실패 — 다른 행 진행 + at-least-once 로 다음 cycle 재발행 가능")
    void mark_failure_does_not_block_other_rows() {
        CouponIssueOutbox o1 = newOutbox(1L, "code-1", "idem-1");
        CouponIssueOutbox o2 = newOutbox(2L, "code-2", "idem-2");
        when(outboxRepository.findUnpublishedForUpdate(100)).thenReturn(List.of(o1, o2));
        doNothing().when(publisher).publish(any());

        // o1 의 markPublished 트랜잭션이 실패하도록 — save 첫 호출에서 RuntimeException, 두 번째는 인자 echo.
        // executeWithoutResult 의 stub 은 setUp 에서 이미 callback 그대로 실행 — RuntimeException 자동 전파.
        when(outboxRepository.save(any(CouponIssueOutbox.class)))
            .thenThrow(new RuntimeException("simulated DB error"))
            .thenAnswer(inv -> inv.getArgument(0));

        int result = poller.pollOnce();

        // o1 markPublished 실패 → 0 카운트, o2 정상 → 1 카운트
        assertThat(result).isEqualTo(1);
        verify(publisher, times(1)).publish(o1);
        verify(publisher, times(1)).publish(o2);
    }

    private CouponIssueOutbox newOutbox(long id, String code, String idem) {
        CouponIssuedEvent event = new CouponIssuedEvent(
            1L, 100L + id, CouponCode.generate(), idem, Instant.now());
        return CouponIssueOutbox.reconstitute(
            id, event, false, null, 0L, Instant.now(), Instant.now());
    }
}
