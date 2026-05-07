package com.promotion.serverc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.promotion.common.coupon.CouponCode;
import com.promotion.serverc.domain.Coupon;
import com.promotion.serverc.domain.CouponRepository;
import com.promotion.serverc.domain.exception.CouponNotFoundException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class RedeemCouponServiceTest {

    private static final CouponCode CODE = new CouponCode("ABCDEFGHJKMN");
    private static final long OWNER_ID = 4242L;
    private static final long OTHER_USER_ID = 7777L;
    private static final long EVENT_ID = 1L;
    private static final String IDEM = "idem-redeem";

    @Test
    @DisplayName("정상 redeem — newlyRedeemed=true + DB save 호출")
    void redeem_marks_used() {
        Coupon coupon = newReconstituted(CODE, OWNER_ID, /* usedAt */ null, /* version */ 0L);
        CouponRepository repo = mock(CouponRepository.class);
        when(repo.findByCode(CODE)).thenReturn(Optional.of(coupon));
        when(repo.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));
        RedeemCouponService service = new RedeemCouponService(repo);

        RedeemResult result = service.redeem(new RedeemCommand(CODE, OWNER_ID, IDEM));

        assertThat(result.newlyRedeemed()).isTrue();
        assertThat(result.code()).isEqualTo(CODE);
        assertThat(result.userId()).isEqualTo(OWNER_ID);
        assertThat(result.redeemedAt()).isNotNull();
        verify(repo).save(any(Coupon.class));
        assertThat(coupon.isUsed()).isTrue();
    }

    @Test
    @DisplayName("코드 없음 — CouponNotFoundException + save 호출 없음")
    void redeem_throws_when_code_missing() {
        CouponRepository repo = mock(CouponRepository.class);
        when(repo.findByCode(CODE)).thenReturn(Optional.empty());
        RedeemCouponService service = new RedeemCouponService(repo);

        assertThatThrownBy(() -> service.redeem(new RedeemCommand(CODE, OWNER_ID, IDEM)))
            .isInstanceOf(CouponNotFoundException.class);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("다른 user 의 쿠폰 — CouponNotFoundException 으로 마스킹 (보안)")
    void redeem_masks_ownership_mismatch() {
        Coupon coupon = newReconstituted(CODE, OWNER_ID, null, 0L);
        CouponRepository repo = mock(CouponRepository.class);
        when(repo.findByCode(CODE)).thenReturn(Optional.of(coupon));
        RedeemCouponService service = new RedeemCouponService(repo);

        // OTHER_USER_ID 가 OWNER_ID 의 쿠폰에 접근 → 다른 user 의 코드 존재 누설 방지로 404 마스킹.
        assertThatThrownBy(() -> service.redeem(new RedeemCommand(CODE, OTHER_USER_ID, IDEM)))
            .isInstanceOf(CouponNotFoundException.class);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("같은 user 의 멱등 재호출 — 200 + 기존 redeemedAt + save 호출 없음")
    void redeem_returns_existing_when_already_redeemed_by_same_user() {
        Instant existingRedeemedAt = Instant.parse("2026-05-07T09:00:00Z");
        Coupon coupon = newReconstituted(CODE, OWNER_ID, existingRedeemedAt, 1L);
        CouponRepository repo = mock(CouponRepository.class);
        when(repo.findByCode(CODE)).thenReturn(Optional.of(coupon));
        RedeemCouponService service = new RedeemCouponService(repo);

        RedeemResult result = service.redeem(new RedeemCommand(CODE, OWNER_ID, IDEM));

        assertThat(result.newlyRedeemed()).isFalse();
        assertThat(result.redeemedAt()).isEqualTo(existingRedeemedAt);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("save 가 OptimisticLockingFailureException → 그대로 throw (handler 가 409 매핑)")
    void redeem_propagates_optimistic_lock_failure() {
        Coupon coupon = newReconstituted(CODE, OWNER_ID, null, 0L);
        CouponRepository repo = mock(CouponRepository.class);
        when(repo.findByCode(CODE)).thenReturn(Optional.of(coupon));
        when(repo.save(any(Coupon.class)))
            .thenThrow(new OptimisticLockingFailureException("stale version"));
        RedeemCouponService service = new RedeemCouponService(repo);

        assertThatThrownBy(() -> service.redeem(new RedeemCommand(CODE, OWNER_ID, IDEM)))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private static Coupon newReconstituted(
        CouponCode code, long userId, Instant usedAt, long version) {
        return Coupon.reconstitute(
            1L, code, userId, EVENT_ID, "idem-issued",
            Instant.parse("2026-05-07T08:00:00Z"), usedAt, version);
    }
}
