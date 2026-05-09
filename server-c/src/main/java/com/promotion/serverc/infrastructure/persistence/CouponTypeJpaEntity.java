package com.promotion.serverc.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "coupon_type")
public class CouponTypeJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coupon_type_id")
    private Long couponTypeId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "discount_rate", nullable = false)
    private int discountRate;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected CouponTypeJpaEntity() {}

    public CouponTypeJpaEntity(Long eventId, String name, int discountRate) {
        this.eventId = eventId;
        this.name = name;
        this.discountRate = discountRate;
    }

    public Long getCouponTypeId() { return couponTypeId; }
    public Long getEventId() { return eventId; }
    public String getName() { return name; }
    public int getDiscountRate() { return discountRate; }
}
