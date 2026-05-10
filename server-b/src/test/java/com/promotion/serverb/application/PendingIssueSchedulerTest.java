package com.promotion.serverb.application;

import com.promotion.common.coupon.CouponIssueRequestPayload;
import com.promotion.serverb.domain.IssuePendingStatus;
import com.promotion.serverb.domain.PendingIssue;
import com.promotion.serverb.infrastructure.client.UserCouponClient;
import com.promotion.serverb.infrastructure.kafka.IssueRequestPublisher;
import com.promotion.serverb.infrastructure.redis.RedisIssueRequestStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-008 보완 스케줄러 — C 조회 → 재발행 (cap) → FAILED 마감 분기 검증.
 */
@ExtendWith(MockitoExtension.class)
class PendingIssueSchedulerTest {

    @Mock
    private RedisIssueRequestStore store;

    @Mock
    private UserCouponClient.Lookup lookup;

    @Mock
    private IssueRequestPublisher publisher;

    private SimpleMeterRegistry meterRegistry;
    private PendingIssueScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new PendingIssueScheduler(store, lookup, publisher, meterRegistry, 10L, 50, 3);
    }

    private PendingIssue stalePending(int attempts) {
        return new PendingIssue(
                "req-1", 1L, 100L, 10L,
                IssuePendingStatus.PENDING, Instant.now().minusSeconds(15), attempts);
    }

    @Test
    void republishesWhenAttemptsBelowCap() {
        PendingIssue p = stalePending(1);
        when(store.findPendingOlderThan(anyLong(), eq(50))).thenReturn(List.of(p));
        when(lookup.findOne(1L, 10L)).thenReturn(Optional.empty());
        when(store.recordRepublish(eq(1L), eq(10L), any())).thenReturn(2);

        scheduler.run();

        verify(publisher).publishForScheduler(any(CouponIssueRequestPayload.class));
        verify(store).recordRepublish(eq(1L), eq(10L), any());
        verify(store, never()).markResult(anyLong(), anyLong(), any(), any());
        assertEquals(1.0, meterRegistry.get("pending.scheduler.republish").counter().count());
    }

    @Test
    void marksFailedWhenAttemptsAtCap() {
        PendingIssue p = stalePending(3);  // already at cap
        when(store.findPendingOlderThan(anyLong(), eq(50))).thenReturn(List.of(p));
        when(lookup.findOne(1L, 10L)).thenReturn(Optional.empty());

        scheduler.run();

        verify(store).markResult(eq(1L), eq(10L), eq(IssuePendingStatus.FAILED), isNull());
        verify(publisher, never()).publishForScheduler(any());
        verify(store, never()).recordRepublish(anyLong(), anyLong(), any());
        assertEquals(1.0, meterRegistry.get("pending.scheduler.give_up").counter().count());
    }

    @Test
    void countsAttemptEvenIfPublishThrows() {
        // publish throw 해도 counter 는 이미 INCR 된 상태 — cap 이 수렴 (영구 publish 장애 시나리오).
        PendingIssue p = stalePending(1);
        when(store.findPendingOlderThan(anyLong(), eq(50))).thenReturn(List.of(p));
        when(lookup.findOne(1L, 10L)).thenReturn(Optional.empty());
        when(store.recordRepublish(eq(1L), eq(10L), any())).thenReturn(2);
        org.mockito.Mockito.doThrow(new IllegalStateException("kafka down"))
                .when(publisher).publishForScheduler(any());

        scheduler.run();

        verify(store).recordRepublish(eq(1L), eq(10L), any());
        verify(store, never()).markResult(anyLong(), anyLong(), any(), any());
        assertEquals(1.0, meterRegistry.get("pending.scheduler.republish").counter().count());
    }

    @Test
    void syncsSuccessFromC() {
        PendingIssue p = stalePending(1);
        when(store.findPendingOlderThan(anyLong(), eq(50))).thenReturn(List.of(p));
        when(lookup.findOne(1L, 10L)).thenReturn(Optional.of(
                new UserCouponClient.Lookup.Result(1L, 100L, 10L, "ABC123456789", "SUCCESS")));

        scheduler.run();

        verify(store).markResult(1L, 10L, IssuePendingStatus.SUCCESS, "ABC123456789");
        verify(publisher, never()).publishForScheduler(any());
    }

    @Test
    void syncsSoldOutFromC() {
        PendingIssue p = stalePending(2);
        when(store.findPendingOlderThan(anyLong(), eq(50))).thenReturn(List.of(p));
        when(lookup.findOne(1L, 10L)).thenReturn(Optional.of(
                new UserCouponClient.Lookup.Result(1L, 100L, 10L, "X-placeholder", "SOLD_OUT")));

        scheduler.run();

        verify(store).markResult(1L, 10L, IssuePendingStatus.SOLD_OUT, "X-placeholder");
        verify(publisher, never()).publishForScheduler(any());
    }

    /** lookup 자체가 실패 (네트워크 등) — 다음 cycle 에 재시도하도록 zset 유지, markResult/republish 안 함. */
    @Test
    void retainsPendingOnLookupException() {
        PendingIssue p = stalePending(1);
        when(store.findPendingOlderThan(anyLong(), eq(50))).thenReturn(List.of(p));
        when(lookup.findOne(anyLong(), anyLong())).thenThrow(new RuntimeException("conn"));

        scheduler.run();

        verify(store, never()).markResult(anyLong(), anyLong(), any(), any());
        verify(store, never()).recordRepublish(anyLong(), anyLong(), any());
        verify(publisher, never()).publishForScheduler(any());
    }

    @Test
    void doesNothingWhenNoStalePending() {
        when(store.findPendingOlderThan(anyLong(), anyInt())).thenReturn(List.of());

        scheduler.run();

        verify(store, never()).markResult(anyLong(), anyLong(), any(), any());
        verify(publisher, never()).publishForScheduler(any());
    }

    @Test
    void processesMultipleStaleEntriesIndependently() {
        PendingIssue belowCap = new PendingIssue(
                "req-1", 1L, 100L, 10L, IssuePendingStatus.PENDING, Instant.now().minusSeconds(15), 1);
        PendingIssue atCap = new PendingIssue(
                "req-2", 2L, 100L, 10L, IssuePendingStatus.PENDING, Instant.now().minusSeconds(15), 3);
        when(store.findPendingOlderThan(anyLong(), eq(50))).thenReturn(List.of(belowCap, atCap));
        when(lookup.findOne(1L, 10L)).thenReturn(Optional.empty());
        when(lookup.findOne(2L, 10L)).thenReturn(Optional.empty());
        when(store.recordRepublish(eq(1L), eq(10L), any())).thenReturn(2);

        scheduler.run();

        verify(publisher, times(1)).publishForScheduler(any());
        verify(store).recordRepublish(eq(1L), eq(10L), any());
        verify(store).markResult(eq(2L), eq(10L), eq(IssuePendingStatus.FAILED), isNull());
    }
}
