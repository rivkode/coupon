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
import com.promotion.serverc.infrastructure.redis.CouponAvailabilityCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
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
 * Kafka consumer 트랜잭션의 비즈니스 로직 (ADR-002 / ADR-003 / ADR-011).
 *
 * <p>핵심 검증:
 * <ul>
 *   <li>비관적 락 경로(`findForUpdate`) 가 호출되는지 — 실제 락 동작은 MySQL 환경 부하로</li>
 *   <li>1 트랜잭션 내 (UNIQUE 단락 + event 유효성 + inventory 차감 + user_coupon + outbox) 흐름</li>
 *   <li>UNIQUE constraint race 시 멱등 (DataIntegrityViolation 흡수, outbox 발행 안 함)</li>
 *   <li>ADR-011: afterCommit hook 으로 inventory fresh read → 0 이면 negative cache write</li>
 * </ul>
 *
 * <p>TransactionSynchronizationManager 는 Spring tx 컨텍스트가 필요해 init/clear 로 시뮬레이트.
 * `fireAfterCommit()` 으로 등록된 hook 을 수동 트리거.
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

    @Mock
    private CouponAvailabilityCache availabilityCache;

    private CouponIssueProcessor processor;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        processor = new CouponIssueProcessor(
                userCouponRepository, inventoryRepository, eventRepository, outboxRepository,
                objectMapper, availabilityCache);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    /** afterCommit hook 을 수동 트리거. */
    private void fireAfterCommit() {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);
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

        verifyNoInteractions(eventRepository, inventoryRepository, outboxRepository, availabilityCache);
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
        verifyNoInteractions(availabilityCache);
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

    /** ADR-011: SOLD_OUT 경로에서 afterCommit hook 이 fresh read → 재고 0 → cache write. */
    @Test
    void writesAvailabilityCacheOnSoldOutPath() {
        when(userCouponRepository.existsByUserIdAndCouponTypeId(1L, 10L)).thenReturn(false);
        EventJpaEntity event = new EventJpaEntity(
                "concert", null, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        CouponTypeInventoryJpaEntity inv = new CouponTypeInventoryJpaEntity(100L, 10L, 1);
        inv.decrement(); // pre-empty
        when(inventoryRepository.findForUpdate(100L, 10L)).thenReturn(Optional.of(inv));
        // afterCommit fresh read 가 0 인 row 를 본다
        when(inventoryRepository.findByEventIdAndCouponTypeId(100L, 10L)).thenReturn(Optional.of(inv));

        processor.process(new CouponIssueRequestPayload("req-1", 1L, 100L, 10L, Instant.now()));
        fireAfterCommit();

        verify(availabilityCache).markSoldOut(100L, 10L);
    }

    /** ADR-011: SUCCESS + 마지막 1장 — afterCommit 가 0 을 발견 → cache write. */
    @Test
    void writesAvailabilityCacheWhenLastOneIssued() {
        when(userCouponRepository.existsByUserIdAndCouponTypeId(1L, 10L)).thenReturn(false);
        EventJpaEntity event = new EventJpaEntity(
                "concert", null, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        CouponTypeInventoryJpaEntity inv = new CouponTypeInventoryJpaEntity(100L, 10L, 1);
        when(inventoryRepository.findForUpdate(100L, 10L)).thenReturn(Optional.of(inv));
        when(inventoryRepository.findByEventIdAndCouponTypeId(100L, 10L)).thenReturn(Optional.of(inv));

        processor.process(new CouponIssueRequestPayload("req-1", 1L, 100L, 10L, Instant.now()));
        fireAfterCommit();

        // 발급 자체는 SUCCESS, decrement 후 availableCount=0 → cache write
        assertEquals(0, inv.getAvailableCount());
        verify(availabilityCache).markSoldOut(100L, 10L);
    }

    /**
     * ADR-011 ghost write 방지 — 트랜잭션이 rollback (Spring 표준 동작) 되면 afterCommit hook 자체가
     * 발화되지 않으므로 cache write 도 일어나지 않는다. 본 테스트는 hook 미발화를 시뮬레이트.
     */
    @Test
    void doesNotWriteCacheWhenTransactionRollsBack() {
        when(userCouponRepository.existsByUserIdAndCouponTypeId(1L, 10L)).thenReturn(false);
        EventJpaEntity event = new EventJpaEntity(
                "concert", null, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        CouponTypeInventoryJpaEntity inv = new CouponTypeInventoryJpaEntity(100L, 10L, 1);
        when(inventoryRepository.findForUpdate(100L, 10L)).thenReturn(Optional.of(inv));

        processor.process(new CouponIssueRequestPayload("req-1", 1L, 100L, 10L, Instant.now()));
        // afterCommit 미호출 = 트랜잭션 롤백 시뮬레이트. 실 환경에선 Spring tx 매니저가 알아서 미발화.

        verifyNoInteractions(availabilityCache);
    }

    /** SUCCESS + 잔여 있음 → afterCommit 가 0 이 아님 → cache write 안 함. */
    @Test
    void skipsCacheWhenInventoryRemaining() {
        when(userCouponRepository.existsByUserIdAndCouponTypeId(1L, 10L)).thenReturn(false);
        EventJpaEntity event = new EventJpaEntity(
                "concert", null, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        CouponTypeInventoryJpaEntity inv = new CouponTypeInventoryJpaEntity(100L, 10L, 5);
        when(inventoryRepository.findForUpdate(100L, 10L)).thenReturn(Optional.of(inv));
        when(inventoryRepository.findByEventIdAndCouponTypeId(100L, 10L)).thenReturn(Optional.of(inv));

        processor.process(new CouponIssueRequestPayload("req-1", 1L, 100L, 10L, Instant.now()));
        fireAfterCommit();

        assertEquals(4, inv.getAvailableCount());
        verify(availabilityCache, never()).markSoldOut(anyLong(), anyLong());
    }
}
