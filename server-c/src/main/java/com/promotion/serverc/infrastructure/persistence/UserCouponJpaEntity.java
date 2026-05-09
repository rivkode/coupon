package com.promotion.serverc.infrastructure.persistence;

import com.promotion.serverc.domain.UserCouponStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_coupon")
public class UserCouponJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_coupon_id")
    private Long userCouponId;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "coupon_type_id", nullable = false)
    private Long couponTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserCouponStatus status;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected UserCouponJpaEntity() {}

    public UserCouponJpaEntity(String code, Long userId, Long eventId, Long couponTypeId,
                               UserCouponStatus status, LocalDateTime issuedAt) {
        this.code = code;
        this.userId = userId;
        this.eventId = eventId;
        this.couponTypeId = couponTypeId;
        this.status = status;
        this.issuedAt = issuedAt;
    }

    /** Redeem — ADR-007 낙관적 락. 이미 사용됐거나 SUCCESS 가 아니면 false. */
    public boolean markUsed(LocalDateTime now) {
        if (status != UserCouponStatus.SUCCESS) {
            return false;
        }
        if (usedAt != null) {
            return false;
        }
        this.usedAt = now;
        this.status = UserCouponStatus.USED;
        return true;
    }

    public Long getUserCouponId() { return userCouponId; }
    public String getCode() { return code; }
    public Long getUserId() { return userId; }
    public Long getEventId() { return eventId; }
    public Long getCouponTypeId() { return couponTypeId; }
    public UserCouponStatus getStatus() { return status; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public Long getVersion() { return version; }
}
