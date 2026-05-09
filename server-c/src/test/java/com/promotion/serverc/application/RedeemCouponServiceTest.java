package com.promotion.serverc.application;

import com.promotion.serverc.domain.UserCouponStatus;
import com.promotion.serverc.domain.exception.CouponNotFoundException;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaEntity;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redeem 정책 단위 테스트 (ADR-007).
 *
 * <p>정책:
 * <ul>
 *   <li>같은 user 의 USED 쿠폰 재호출 → 멱등 응답 (newlyRedeemed=false), DB save 없음</li>
 *   <li>다른 user → 코드 존재 여부 마스킹 (404)</li>
 *   <li>SUCCESS 가 아닌 상태 (SOLD_OUT/FAILED) → IllegalStateException (409)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RedeemCouponServiceTest {

    @Mock
    private UserCouponJpaRepository repository;

    @InjectMocks
    private RedeemCouponService service;

    @Test
    void redeemsSuccessfully() {
        UserCouponJpaEntity uc = new UserCouponJpaEntity(
                "ABC123456789", 1L, 100L, 10L, UserCouponStatus.SUCCESS, LocalDateTime.now());
        when(repository.findByCode("ABC123456789")).thenReturn(Optional.of(uc));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        RedeemResult result = service.redeem(new RedeemCommand("ABC123456789", 1L));

        assertTrue(result.newlyRedeemed());
        assertEquals(UserCouponStatus.USED, uc.getStatus());
        verify(repository).save(uc);
    }

    @Test
    void masksOwnershipMismatchAsNotFound() {
        UserCouponJpaEntity uc = new UserCouponJpaEntity(
                "ABC123456789", 99L, 100L, 10L, UserCouponStatus.SUCCESS, LocalDateTime.now());
        when(repository.findByCode("ABC123456789")).thenReturn(Optional.of(uc));

        assertThrows(CouponNotFoundException.class,
                () -> service.redeem(new RedeemCommand("ABC123456789", 1L)));
        verify(repository, never()).save(any());
    }

    @Test
    void returnsNotFoundWhenCodeAbsent() {
        when(repository.findByCode("NONE000000XX")).thenReturn(Optional.empty());

        assertThrows(CouponNotFoundException.class,
                () -> service.redeem(new RedeemCommand("NONE000000XX", 1L)));
    }

    @Test
    void idempotentReplayOnAlreadyUsed() {
        UserCouponJpaEntity uc = new UserCouponJpaEntity(
                "ABC123456789", 1L, 100L, 10L, UserCouponStatus.SUCCESS, LocalDateTime.now());
        // markUsed 로 USED 상태 + usedAt 세팅
        LocalDateTime originalUsedAt = LocalDateTime.now().minusMinutes(5);
        uc.markUsed(originalUsedAt);
        when(repository.findByCode("ABC123456789")).thenReturn(Optional.of(uc));

        RedeemResult result = service.redeem(new RedeemCommand("ABC123456789", 1L));

        assertFalse(result.newlyRedeemed());
        assertEquals(originalUsedAt, result.redeemedAt());
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsRedeemOnSoldOutStatus() {
        UserCouponJpaEntity uc = new UserCouponJpaEntity(
                "ABC123456789", 1L, 100L, 10L, UserCouponStatus.SOLD_OUT, LocalDateTime.now());
        when(repository.findByCode("ABC123456789")).thenReturn(Optional.of(uc));

        assertThrows(IllegalStateException.class,
                () -> service.redeem(new RedeemCommand("ABC123456789", 1L)));
    }
}
