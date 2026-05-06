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
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class IssueRequestServiceTest {

    private static final Instant FAR_PAST = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final CouponCode CODE = new CouponCode("ABCD12345678");

    private IssueRequestRepository issueRequestRepository;
    private EventRepository eventRepository;
    private CouponIssuingClient client;
    private TransactionTemplate transactionTemplate;
    private IssueRequestService service;

    @BeforeEach
    void setUp() {
        issueRequestRepository = mock(IssueRequestRepository.class);
        eventRepository = mock(EventRepository.class);
        client = mock(CouponIssuingClient.class);
        transactionTemplate = mock(TransactionTemplate.class);

        // TransactionTemplate.execute 가 callback 을 그대로 실행 — split-tx 흐름 시뮬레이션.
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });

        // IssueRequestRepository.save 는 그대로 반환 (실제 DB 없음).
        when(issueRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 정상 event — 항상 open.
        when(eventRepository.findById(anyLong())).thenReturn(Optional.of(
            Event.reconstitute(1L, "Concert", 10_000, FAR_PAST, FAR_FUTURE)));

        service = new IssueRequestService(
            issueRequestRepository, eventRepository, client, transactionTemplate);
    }

    @Test
    @DisplayName("정상 흐름 — RECEIVED → FORWARDED → SUCCEEDED + couponCode")
    void issued_path_marks_succeeded() {
        when(client.issue(anyLong(), anyLong(), anyString())).thenReturn(IssueResult.issued(CODE));

        IssueOutcome outcome = service.issue(new IssueCommand(1L, 100L, "idem-1"));

        assertThat(outcome.downstreamStatus()).isEqualTo(IssueStatus.ISSUED);
        IssueRequest req = outcome.issueRequest();
        assertThat(req.getStatus()).isEqualTo(IssueRequestStatus.SUCCEEDED);
        assertThat(req.getCouponCode()).isEqualTo(CODE);
        assertThat(req.getFailureReason()).isNull();
        // tx1 + tx2 두 번 save.
        verify(issueRequestRepository, times(2)).save(any(IssueRequest.class));
        verify(client, times(1)).issue(eq(1L), eq(100L), eq("idem-1"));
    }

    @Test
    @DisplayName("SOLD_OUT — markFailed + downstreamStatus SOLD_OUT")
    void sold_out_marks_failed() {
        when(client.issue(anyLong(), anyLong(), anyString())).thenReturn(IssueResult.soldOut());

        IssueOutcome outcome = service.issue(new IssueCommand(1L, 100L, "idem"));

        assertThat(outcome.downstreamStatus()).isEqualTo(IssueStatus.SOLD_OUT);
        assertThat(outcome.issueRequest().getStatus()).isEqualTo(IssueRequestStatus.FAILED);
        assertThat(outcome.issueRequest().getFailureReason()).isEqualTo("stock exhausted");
        assertThat(outcome.isDownstreamUnavailable()).isFalse();
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
    }

    @Test
    @DisplayName("client.issue 의 RuntimeException → INTERNAL_ERROR fallback (안전망)")
    void client_runtime_exception_is_caught_and_mapped_to_internal_error() {
        when(client.issue(anyLong(), anyLong(), anyString()))
            .thenThrow(new RuntimeException("simulated stub blowup"));

        IssueOutcome outcome = service.issue(new IssueCommand(1L, 100L, "idem"));

        assertThat(outcome.downstreamStatus()).isEqualTo(IssueStatus.INTERNAL_ERROR);
        assertThat(outcome.issueRequest().getStatus()).isEqualTo(IssueRequestStatus.FAILED);
        // 정확한 prefix + simple class name 까지 검증 — 회귀 방지.
        assertThat(outcome.issueRequest().getFailureReason()).isEqualTo("client-exception: RuntimeException");
    }

    @Test
    @DisplayName("event 미존재 → IllegalArgumentException, client 미호출, tx 미시작")
    void missing_event_throws_before_any_tx_or_client_call() {
        when(eventRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(new IssueCommand(1L, 999L, "idem")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("event not found");

        verify(transactionTemplate, never()).execute(any());
        verify(issueRequestRepository, never()).save(any());
        verify(client, never()).issue(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("event 종료 시각 이후 → IllegalStateException")
    void event_not_open_throws() {
        Instant past = Instant.parse("2020-06-01T00:00:00Z");
        when(eventRepository.findById(anyLong())).thenReturn(Optional.of(
            Event.reconstitute(1L, "ended", 10, FAR_PAST, past)));

        assertThatThrownBy(() -> service.issue(new IssueCommand(1L, 100L, "idem")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not open");

        verify(client, never()).issue(anyLong(), anyLong(), anyString());
    }
}
