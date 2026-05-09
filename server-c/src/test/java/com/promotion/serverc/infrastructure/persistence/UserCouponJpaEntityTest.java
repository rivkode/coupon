package com.promotion.serverc.infrastructure.persistence;

import com.promotion.serverc.domain.UserCouponStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserCouponJpaEntityTest {

    @Test
    void marksUsedFromSuccess() {
        UserCouponJpaEntity uc = new UserCouponJpaEntity(
                "ABC12345WXYZ", 1L, 1L, 1L, UserCouponStatus.SUCCESS, LocalDateTime.now());

        boolean ok = uc.markUsed(LocalDateTime.now());

        assertTrue(ok);
        assertEquals(UserCouponStatus.USED, uc.getStatus());
        assertNotNull(uc.getUsedAt());
    }

    @Test
    void rejectsRedeemWhenSoldOut() {
        UserCouponJpaEntity uc = new UserCouponJpaEntity(
                "ABC12345WXYZ", 1L, 1L, 1L, UserCouponStatus.SOLD_OUT, LocalDateTime.now());

        assertFalse(uc.markUsed(LocalDateTime.now()));
        assertEquals(UserCouponStatus.SOLD_OUT, uc.getStatus());
    }

    @Test
    void idempotentRedeemDoesNotChangeUsedAt() {
        LocalDateTime issuedAt = LocalDateTime.now();
        UserCouponJpaEntity uc = new UserCouponJpaEntity(
                "ABC12345WXYZ", 1L, 1L, 1L, UserCouponStatus.SUCCESS, issuedAt);
        uc.markUsed(issuedAt.plusMinutes(1));

        boolean second = uc.markUsed(issuedAt.plusMinutes(2));

        assertFalse(second);
        assertEquals(UserCouponStatus.USED, uc.getStatus());
    }
}
