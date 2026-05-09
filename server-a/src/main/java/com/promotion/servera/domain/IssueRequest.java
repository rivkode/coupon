package com.promotion.servera.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 요청 로그 도메인 (audit). status 는 A 가 B 에게 위임할 때의 결과 (ACCEPTED / DUPLICATE / REJECTED).
 * 발급 결과의 권위는 server-c 의 user_coupon.
 */
public class IssueRequest {

    private Long id;
    private String requestId;
    private Long userId;
    private Long eventId;
    private Long couponTypeId;
    private IssueRequestStatus status;
    private Instant createdAt;

    private IssueRequest() {}

    public static IssueRequest of(Long userId, Long eventId, Long couponTypeId,
                                  IssueRequestStatus status, Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(couponTypeId, "couponTypeId");
        Objects.requireNonNull(status, "status");
        IssueRequest r = new IssueRequest();
        r.requestId = UUID.randomUUID().toString();
        r.userId = userId;
        r.eventId = eventId;
        r.couponTypeId = couponTypeId;
        r.status = status;
        r.createdAt = now;
        return r;
    }

    public static IssueRequest reconstitute(Long id, String requestId, Long userId, Long eventId,
                                            Long couponTypeId, IssueRequestStatus status, Instant createdAt) {
        IssueRequest r = new IssueRequest();
        r.id = id;
        r.requestId = requestId;
        r.userId = userId;
        r.eventId = eventId;
        r.couponTypeId = couponTypeId;
        r.status = status;
        r.createdAt = createdAt;
        return r;
    }

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public Long getUserId() { return userId; }
    public Long getEventId() { return eventId; }
    public Long getCouponTypeId() { return couponTypeId; }
    public IssueRequestStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
