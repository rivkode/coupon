package com.promotion.servera.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.promotion.common.coupon.CouponCode;
import com.promotion.common.coupon.IssueResult;
import com.promotion.common.coupon.IssueStatus;
import com.promotion.servera.domain.Event;
import com.promotion.servera.domain.EventRepository;
import com.promotion.servera.domain.IssueRequest;
import com.promotion.servera.domain.IssueRequestRepository;
import com.promotion.servera.domain.IssueRequestStatus;
import com.promotion.servera.infrastructure.batch.IssueRequestBatchQueue;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase C 의 IssueRequestService 동작:
 * <ul>
 *   <li>정상: client 호출 → in-memory 마감 → batchQueue.tryEnqueue 1회. DB save 없음.</li>
 *   <li>큐 가득 (방어선 2): tryEnqueue=false → 동기 INSERT fallback (repository.save 1회).</li>
 *   <li>event 검증은 batch 큐 진입 전 — 미존재 / 종료 시 throw.</li>
 * </ul>
 */
class IssueRequestServiceTest {

    private static final Instant FAR_PAST = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final CouponCode CODE = new CouponCode("ABCD12345678");

    private IssueRequestRepository issueRequestRepository;
    private EventRepository eventRepository;
    private CouponIssuingClient client;
    private TransactionTemplate transactionTemplate;
    private IssueRequestBatchQueue batchQueue;
    private IssueRequestService service;

    @BeforeEach
    void setUp() {
        issueRequestRepository = mock(IssueRequestRepository.class);
        eventRepository = mock(EventRepository.class);
        client = mock(CouponIssuingClient.class);
        transactionTemplate = mock(TransactionTemplate.class);
        batchQueue = mock(IssueRequestBatchQueue.class);

        // 큐 enqueue 기본 성공 — 일반 정상 흐름.
        when(batchQueue.tryEnqueue(any(IssueRequest.class))).thenReturn(true);

        // TransactionTemplate.execute 가 callback 그대로 실행 — fallback save 시뮬레이션.
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });

        // IssueRequestRepository.save 는 인자 그대로 반환 (실제 DB 없음).
        when(issueRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 정상 event — 항상 open.
        when(eventRepository.findById(anyLong())).thenReturn(Optional.of(
            Event.reconstitute(1L, "Concert", 10_000, FAR_PAST, FAR_FUTURE)));

        service = new IssueRequestService(
            issueRequestRepository, eventRepository, client, transactionTemplate, batchQueue);
    }

    @Test
    @DisplayName("정상 흐름 — FORWARDED → SUCCEEDED, batchQueue enqueue 1회, DB save 없음")
    void issued_path_marks_succeeded_and_enqueues_to_batch() {
        when(client.issue(anyLong(), anyLong(), anyString())).thenReturn(IssueResult.issued(CODE));

        IssueOutcome outcome = service.issue(new IssueCommand(1L, 100L, "idem-1"));

        assertThat(outcome.downstreamStatus()).isEqualTo(IssueStatus.ISSUED);
        IssueRequest req = outcome.issueRequest();
        assertThat(req.getStatus()).isEqualTo(IssueRequestStatus.SUCCEEDED);
        assertThat(req.getCouponCode()).isEqualTo(CODE);
        assertThat(req.getFailureReason()).isNull();
        // Phase C — 큐에 1회만 enqueue. DB save 는 없음.
        verify(batchQueue, times(1)).tryEnqueue(any(IssueRequest.class));
        verify(issueRequestRepository, never()).save(any());
        verify(client, times(1)).issue(eq(1L), eq(100L), eq("idem-1"));
    }

    @Test
    @DisplayName("SOLD_OUT — markFailed + batchQueue enqueue 1회")
    void sold_out_marks_failed_and_enqueues() {
        when(client.issue(anyLong(), anyLong(), anyString())).thenReturn(IssueResult.soldOut());

        IssueOutcome outcome = service.issue(new IssueCommand(1L, 100L, "idem"));

        assertThat(outcome.downstreamStatus()).isEqualTo(IssueStatus.SOLD_OUT);
        assertThat(outcome.issueRequest().getStatus()).isEqualTo(IssueRequestStatus.FAILED);
        assertThat(outcome.issueRequest().getFailureReason()).isEqualTo("stock exhausted");
        assertThat(outcome.isDownstreamUnavailable()).isFalse();
        verify(batchQueue, times(1)).tryEnqueue(any(IssueRequest.class));
        verify(issueRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("INTERNAL_ERROR (Circuit OPEN / 5xx) — markFailed + downstreamStatus INTERNAL_ERROR")
    void internal_error_marks_failed_and_signals_downstream_unavailable() {
        when(client.issue(anyLong(), anyLong(), anyString()))
            .thenReturn(IssueResult.internalError("circuit-open"));

        IssueOutcome outcome = service.issue(new IssueCommand(1L, 100L, "idem"));

        assertThat(outcome.downstreamStatus()).isEqualTo(IssueStatus.INTERNAL_ERROR);
        assertThat(outcome.isDownstreamUnavailable()).isTrue();
        assertThat(outcome.issueRequest().getStatus()).isEqualTo(IssueRequestStatus.FAILED);
        assertThat(outcome.issueRequest().getFailureReason()).isEqualTo("circuit-open");
        verify(batchQueue, times(1)).tryEnqueue(any(IssueRequest.class));
    }

    @Test
    @DisplayName("client.issue 의 RuntimeException → INTERNAL_ERROR fallback")
    void client_runtime_exception_is_caught_and_mapped_to_internal_error() {
        when(client.issue(anyLong(), anyLong(), anyString()))
            .thenThrow(new RuntimeException("simulated stub blowup"));

        IssueOutcome outcome = service.issue(new IssueCommand(1L, 100L, "idem"));

        assertThat(outcome.downstreamStatus()).isEqualTo(IssueStatus.INTERNAL_ERROR);
        assertThat(outcome.issueRequest().getStatus()).isEqualTo(IssueRequestStatus.FAILED);
        // prefix 만 검증 — RuntimeException 의 simple class name 변경 시 테스트 깨짐 회피.
        assertThat(outcome.issueRequest().getFailureReason()).startsWith("client-exception:");
        verify(batchQueue, times(1)).tryEnqueue(any(IssueRequest.class));
    }

    @Test
    @DisplayName("방어선 2 — 큐 가득 차면 동기 INSERT fallback (repository.save 1회)")
    void queue_full_falls_back_to_sync_insert() {
        when(client.issue(anyLong(), anyLong(), anyString())).thenReturn(IssueResult.issued(CODE));
        when(batchQueue.tryEnqueue(any(IssueRequest.class))).thenReturn(false); // 큐 가득 시뮬레이션

        IssueOutcome outcome = service.issue(new IssueCommand(1L, 100L, "idem"));

        assertThat(outcome.issueRequest().getStatus()).isEqualTo(IssueRequestStatus.SUCCEEDED);
        // tryEnqueue 시도 1회 + 동기 fallback save 1회.
        verify(batchQueue, times(1)).tryEnqueue(any(IssueRequest.class));
        verify(issueRequestRepository, times(1)).save(any(IssueRequest.class));
        verify(transactionTemplate, times(1)).execute(any());
    }

    @Test
    @DisplayName("event 미존재 → IllegalArgumentException, client/큐/save 모두 미호출")
    void missing_event_throws_before_any_side_effect() {
        when(eventRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(new IssueCommand(1L, 999L, "idem")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("event not found");

        verify(batchQueue, never()).tryEnqueue(any());
        verify(transactionTemplate, never()).execute(any());
        verify(issueRequestRepository, never()).save(any());
        verify(client, never()).issue(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("event 종료 시각 이후 → IllegalStateException, 큐 미호출")
    void event_not_open_throws() {
        Instant past = Instant.parse("2020-06-01T00:00:00Z");
        when(eventRepository.findById(anyLong())).thenReturn(Optional.of(
            Event.reconstitute(1L, "ended", 10, FAR_PAST, past)));

        assertThatThrownBy(() -> service.issue(new IssueCommand(1L, 100L, "idem")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not open");

        verify(client, never()).issue(anyLong(), anyLong(), anyString());
        verify(batchQueue, never()).tryEnqueue(any());
    }
}
