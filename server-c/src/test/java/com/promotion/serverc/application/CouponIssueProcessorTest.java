package com.promotion.serverc.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.common.coupon.CouponIssueRequestPayload;
import com.promotion.serverc.domain.UserCouponStatus;
import com.promotion.serverc.infrastructure.persistence.CouponTypeInventoryJpaEntity;
import com.promotion.serverc.infrastructure.persistence.CouponTypeInventoryJpaRepository;
import com.promotion.serverc.infrastructure.persistence.EventJpaEntity;
import com.promotion.serverc.infrastructure.persistence.EventJpaRepository;
import com.promotion.serverc.infrastructure.persistence.OutboxEventJpaEntity;
import com.promotion.serverc.infrastructure.persistence.OutboxEventJpaRepository;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaEntity;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kafka consumer 트랜잭션의 비즈니스 로직 (ADR-002 / ADR-003).
 *
 * <p>핵심 검증:
 * <ul>
 *   <li>비관적 락 경로(`findForUpdate`) 가 호출되는지 — 실제 락 동작은 MySQL 환경 부하로</li>
 *   <li>1 트랜잭션 내 (UNIQUE 단락 + event 유효성 + inventory 차감 + user_coupon + outbox) 흐름</li>
 *   <li>UNIQUE constraint race 시 멱등 (DataIntegrityViolation 흡수, outbox 발행 안 함)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CouponIssueProcessorTest {

    @Mock
    private UserCouponJpaRepository userCouponRepository;

    @Mock
    private CouponTypeInventoryJpaRepository inventoryRepository;

    @Mock
    private EventJpaRepository eventRepository;

    @Mock
    private OutboxEventJpaRepository outboxRepository;

    private CouponIssueProcessor processor;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        processor = new CouponIssueProcessor(
                userCouponRepository, inventoryRepository, eventRepository, outboxRepository, objectMapper);
    }

    @Test
    void issuesAndDecrementsInventoryAndSavesOutbox() {
        when(userCouponRepository.existsByUserIdAndCouponTypeId(1L, 10L)).thenReturn(false);
        EventJpaEntity event = new EventJpaEntity(
                "concert", null, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        CouponTypeInventoryJpaEntity inv = new CouponTypeInventoryJpaEntity(100L, 10L, 100);
        when(inventoryRepository.findForUpdate(100L, 10L)).thenReturn(Optional.of(inv));

        processor.process(new CouponIssueRequestPayload("req-1", 1L, 100L, 10L, Instant.now()));

        // 비관적 락 경로 (findForUpdate) 가 호출됐다.
        verify(inventoryRepository).findForUpdate(100L, 10L);
        // 재고 차감
        assertEquals(99, inv.getAvailableCount());
        // SUCCESS 로 영구 저장 + outbox 적재
        verify(userCouponRepository).save(argThat((UserCouponJpaEntity uc) ->
                uc.getStatus() == UserCouponStatus.SUCCESS && uc.getCode() != null));
        verify(outboxRepository).save(any(OutboxEventJpaEntity.class));
    }

    @Test
    void marksSoldOutWhenInventoryEmpty() {
        when(userCouponRepository.existsByUserIdAndCouponTypeId(1L, 10L)).thenReturn(false);
        EventJpaEntity event = new EventJpaEntity(
                "concert", null, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        CouponTypeInventoryJpaEntity inv = new CouponTypeInventoryJpaEntity(100L, 10L, 1);
        inv.decrement(); // pre-empty
        when(inventoryRepository.findForUpdate(100L, 10L)).thenReturn(Optional.of(inv));

        processor.process(new CouponIssueRequestPayload("req-1", 1L, 100L, 10L, Instant.now()));

        verify(userCouponRepository).save(argThat((UserCouponJpaEntity uc) ->
                uc.getStatus() == UserCouponStatus.SOLD_OUT));
        verify(outboxRepository).save(any(OutboxEventJpaEntity.class));
    }

    @Test
    void skipsSilentlyWhenAlreadyIssued() {
        when(userCouponRepository.existsByUserIdAndCouponTypeId(1L, 10L)).thenReturn(true);

        processor.process(new CouponIssueRequestPayload("req-1", 1L, 100L, 10L, Instant.now()));

        verifyNoInteractions(eventRepository, inventoryRepository, outboxRepository);
        verify(userCouponRepository, never()).save(any());
    }

    @Test
    void marksFailedWhenEventEnded() {
        when(userCouponRepository.existsByUserIdAndCouponTypeId(1L, 10L)).thenReturn(false);
        EventJpaEntity event = new EventJpaEntity(
                "concert", null, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));

        processor.process(new CouponIssueRequestPayload("req-1", 1L, 100L, 10L, Instant.now()));

        verify(inventoryRepository, never()).findForUpdate(anyLong(), anyLong());
        verify(userCouponRepository).save(argThat((UserCouponJpaEntity uc) ->
                uc.getStatus() == UserCouponStatus.FAILED));
    }

    /**
     * UNIQUE(user_id, coupon_type_id) constraint race — 두 메시지가 거의 동시에 처리될 때
     * 한쪽이 DataIntegrityViolation 으로 거부됨. 이때 outbox 발행하지 않고 silent skip — 첫 번째
     * 메시지의 outbox 가 권위.
     */
    @Test
    void absorbsRaceOnUniqueViolation() {
        when(userCouponRepository.existsByUserIdAndCouponTypeId(1L, 10L)).thenReturn(false);
        EventJpaEntity event = new EventJpaEntity(
                "concert", null, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        CouponTypeInventoryJpaEntity inv = new CouponTypeInventoryJpaEntity(100L, 10L, 100);
        when(inventoryRepository.findForUpdate(100L, 10L)).thenReturn(Optional.of(inv));
        when(userCouponRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uk_user_coupon_user_type"));

        processor.process(new CouponIssueRequestPayload("req-1", 1L, 100L, 10L, Instant.now()));

        verify(outboxRepository, never()).save(any());
    }
}
