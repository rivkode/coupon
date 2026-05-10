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
 *   <li>Redis 적재 후 Kafka publish 실패 → ACCEPTED 응답 (ADR-008 — publish 실패는 swallow,
 *       스케줄러가 30s 안에 재발행으로 회복). Redis pending 은 attempts=1 로 남아있음.</li>
 * </ul>
 *
 * <p>SOLD_OUT 단락은 server-a 진입에서 처리 (ADR-011) — B 는 정상 흐름만.
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
     * H1 결정 (ADR-008): publish 실패는 swallow → ACCEPTED 응답 반환. 스케줄러가 cap(=3) 안에서
     * 재발행으로 회복하므로 사용자에게 5xx + 재시도 시 DUPLICATE 의 모호한 흐름을 만들지 않음.
     */
    @Test
    void returnsAcceptedEvenWhenKafkaPublishFails() {
        when(store.savePendingIfAbsent(anyString(), anyLong(), anyLong(), anyLong(), any(Instant.class)))
                .thenReturn(true);
        doThrow(new IllegalStateException("kafka send failed"))
                .when(publisher).publish(any());

        IssueAcceptanceResult result = service.accept(1L, 100L, 10L);

        assertEquals(IssueAcceptanceStatus.ACCEPTED, result.status());
        verify(store).savePendingIfAbsent(anyString(), eq(1L), eq(100L), eq(10L), any(Instant.class));
        verify(publisher).publish(any());
    }
}
