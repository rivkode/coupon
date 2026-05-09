package com.promotion.serverb.application;

import com.promotion.serverb.domain.IssuePendingStatus;
import com.promotion.serverb.domain.PendingIssue;
import com.promotion.serverb.infrastructure.client.UserCouponClient;
import com.promotion.serverb.infrastructure.redis.RedisIssueRequestStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-008 보완 스케줄러 — Kafka publish 실패 / 유실 시 C DB 직접 조회로 결과 회수.
 */
@ExtendWith(MockitoExtension.class)
class PendingIssueSchedulerTest {

    @Mock
    private RedisIssueRequestStore store;

    @Mock
    private UserCouponClient.Lookup lookup;

    private PendingIssueScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PendingIssueScheduler(store, lookup, 10L, 50);
    }

    @Test
    void marksFailedWhenCDoesNotHaveRecord() {
        PendingIssue p = new PendingIssue(
                "req-1", 1L, 100L, 10L,
                IssuePendingStatus.PENDING, Instant.now().minusSeconds(15));
        when(store.findPendingOlderThan(anyLong(), eq(50))).thenReturn(List.of(p));
        when(lookup.findOne(1L, 10L)).thenReturn(Optional.empty());

        scheduler.run();

        verify(store).markResult(eq(1L), eq(10L), eq(IssuePendingStatus.FAILED), isNull());
    }

    @Test
    void syncsSuccessFromC() {
        PendingIssue p = new PendingIssue(
                "req-1", 1L, 100L, 10L,
                IssuePendingStatus.PENDING, Instant.now().minusSeconds(15));
        when(store.findPendingOlderThan(anyLong(), eq(50))).thenReturn(List.of(p));
        when(lookup.findOne(1L, 10L)).thenReturn(Optional.of(
                new UserCouponClient.Lookup.Result(1L, 100L, 10L, "ABC123456789", "SUCCESS")));

        scheduler.run();

        verify(store).markResult(1L, 10L, IssuePendingStatus.SUCCESS, "ABC123456789");
    }

    @Test
    void syncsSoldOutFromC() {
        PendingIssue p = new PendingIssue(
                "req-1", 1L, 100L, 10L,
                IssuePendingStatus.PENDING, Instant.now().minusSeconds(15));
        when(store.findPendingOlderThan(anyLong(), eq(50))).thenReturn(List.of(p));
        when(lookup.findOne(1L, 10L)).thenReturn(Optional.of(
                new UserCouponClient.Lookup.Result(1L, 100L, 10L, "X-placeholder", "SOLD_OUT")));

        scheduler.run();

        verify(store).markResult(1L, 10L, IssuePendingStatus.SOLD_OUT, "X-placeholder");
    }

    /** lookup 자체가 실패 (네트워크 등) — 다음 cycle 에 재시도하도록 zset 유지, markResult 안 함. */
    @Test
    void retainsPendingOnLookupException() {
        PendingIssue p = new PendingIssue(
                "req-1", 1L, 100L, 10L,
                IssuePendingStatus.PENDING, Instant.now().minusSeconds(15));
        when(store.findPendingOlderThan(anyLong(), eq(50))).thenReturn(List.of(p));
        when(lookup.findOne(anyLong(), anyLong())).thenThrow(new RuntimeException("conn"));

        scheduler.run();

        verify(store, never()).markResult(anyLong(), anyLong(), any(), any());
    }

    @Test
    void doesNothingWhenNoStalePending() {
        when(store.findPendingOlderThan(anyLong(), anyInt())).thenReturn(List.of());

        scheduler.run();

        verify(store, never()).markResult(anyLong(), anyLong(), any(), any());
    }
}
