package com.promotion.servera.infrastructure.persistence;

import com.promotion.servera.domain.IssueRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "issue_request")
class IssueRequestJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "coupon_type_id", nullable = false)
    private Long couponTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IssueRequestStatus status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected IssueRequestJpaEntity() {}

    IssueRequestJpaEntity(Long id, String requestId, Long userId, Long eventId,
                          Long couponTypeId, IssueRequestStatus status, Instant createdAt) {
        this.id = id;
        this.requestId = requestId;
        this.userId = userId;
        this.eventId = eventId;
        this.couponTypeId = couponTypeId;
        this.status = status;
        this.createdAt = createdAt;
    }

    Long getId() { return id; }
    String getRequestId() { return requestId; }
    Long getUserId() { return userId; }
    Long getEventId() { return eventId; }
    Long getCouponTypeId() { return couponTypeId; }
    IssueRequestStatus getStatus() { return status; }
    Instant getCreatedAt() { return createdAt; }
}
