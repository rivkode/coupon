package com.promotion.serverc.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CouponTypeInventoryJpaEntityTest {

    @Test
    void decrementsWhenAvailable() {
        CouponTypeInventoryJpaEntity inv = new CouponTypeInventoryJpaEntity(1L, 1L, 3);

        assertTrue(inv.decrement());
        assertEquals(2, inv.getAvailableCount());
        assertTrue(inv.decrement());
        assertTrue(inv.decrement());
    }

    @Test
    void rejectsWhenSoldOut() {
        CouponTypeInventoryJpaEntity inv = new CouponTypeInventoryJpaEntity(1L, 1L, 1);

        assertTrue(inv.decrement());
        assertFalse(inv.decrement());
        assertEquals(0, inv.getAvailableCount());
    }
}
