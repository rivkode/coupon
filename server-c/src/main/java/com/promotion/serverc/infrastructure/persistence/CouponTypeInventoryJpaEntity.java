package com.promotion.serverc.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "coupon_type_inventory")
public class CouponTypeInventoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coupon_type_inventory_id")
    private Long couponTypeInventoryId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "coupon_type_id", nullable = false)
    private Long couponTypeId;

    @Column(name = "total_inventory", nullable = false)
    private int totalInventory;

    @Column(name = "available_count", nullable = false)
    private int availableCount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected CouponTypeInventoryJpaEntity() {}

    public CouponTypeInventoryJpaEntity(Long eventId, Long couponTypeId, int totalInventory) {
        this.eventId = eventId;
        this.couponTypeId = couponTypeId;
        this.totalInventory = totalInventory;
        this.availableCount = totalInventory;
    }

    /** 재고 차감. 0 이하면 false. 호출 측이 비관적 락 안에서 호출해야 함. */
    public boolean decrement() {
        if (availableCount <= 0) {
            return false;
        }
        availableCount--;
        return true;
    }

    public Long getCouponTypeInventoryId() { return couponTypeInventoryId; }
    public Long getEventId() { return eventId; }
    public Long getCouponTypeId() { return couponTypeId; }
    public int getTotalInventory() { return totalInventory; }
    public int getAvailableCount() { return availableCount; }
}
