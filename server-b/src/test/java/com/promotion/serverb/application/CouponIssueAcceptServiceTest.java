package com.promotion.serverb.application;

import com.promotion.common.coupon.CouponIssueRequestPayload;
import com.promotion.common.coupon.IssueAcceptanceResult;
import com.promotion.common.coupon.IssueAcceptanceStatus;
import com.promotion.serverb.infrastructure.kafka.IssueRequestPublisher;
import com.promotion.serverb.infrastructure.redis.RedisIssueRequestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Server B 발급 신청 접수 service 의 핵심 비즈니스 로직.
 *
 * <p>요구사항:
 * <ul>
 *   <li>(user, couponType) 중복 신청 → DUPLICATE 응답, Kafka publish 안 함</li>
 *   <li>최초 신청 → ACCEPTED 응답, Kafka publish 호출</li>
 *   <li>Redis 적재 후 Kafka publish 실패 → 예외 전파, 단 Redis 의 pending 은 적재된 상태로 남아
 *       스케줄러(@Scheduled) 가 보완 가능 (ADR-008)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CouponIssueAcceptServiceTest {

    @Mock
    private RedisIssueRequestStore store;

    @Mock
    private IssueRequestPublisher publisher;

    @InjectMocks
    private CouponIssueAcceptService service;

    @Test
    void returnsAcceptedAndPublishesWhenFirstRequest() {
        when(store.savePendingIfAbsent(anyString(), anyLong(), anyLong(), anyLong(), any(Instant.class)))
                .thenReturn(true);

        IssueAcceptanceResult result = service.accept(1L, 100L, 10L);

        assertEquals(IssueAcceptanceStatus.ACCEPTED, result.status());
        verify(publisher).publish(any(CouponIssueRequestPayload.class));
    }

    @Test
    void returnsDuplicateAndSkipsPublishWhenAlreadyExists() {
        when(store.savePendingIfAbsent(anyString(), anyLong(), anyLong(), anyLong(), any(Instant.class)))
                .thenReturn(false);

        IssueAcceptanceResult result = service.accept(1L, 100L, 10L);

        assertEquals(IssueAcceptanceStatus.DUPLICATE, result.status());
        verify(publisher, never()).publish(any());
    }

    /**
     * 핵심 시나리오 (사용자 요구사항): Redis 에는 적재됐는데 Kafka publish 가 실패한 경우 —
     * IllegalStateException 이 propagate 되어 사용자에게 5xx 가 가지만, Redis 의 pending 은
     * 그대로 남아 PendingIssueScheduler 가 10 초 후 C DB 직접 조회로 보완할 수 있는 상태.
     */
    @Test
    void leavesRedisPendingWhenKafkaPublishFails() {
        when(store.savePendingIfAbsent(anyString(), anyLong(), anyLong(), anyLong(), any(Instant.class)))
                .thenReturn(true);
        doThrow(new IllegalStateException("kafka send failed"))
                .when(publisher).publish(any());

        assertThrows(IllegalStateException.class, () -> service.accept(1L, 100L, 10L));

        // Redis 의 savePendingIfAbsent 는 호출됐고 — pending 적재 완료, 스케줄러가 보완 가능한 상태.
        verify(store).savePendingIfAbsent(anyString(), eq(1L), eq(100L), eq(10L), any(Instant.class));
        verify(publisher).publish(any());
    }
}
