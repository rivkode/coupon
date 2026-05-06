package com.promotion.servera.domain;

import com.promotion.common.coupon.CouponCode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * 발급 요청 도메인 객체 (Server A Aggregate Root).
 * 상태 전이는 markForwarded / markSucceeded / markFailed / reject 메서드를 통해서만 가능. setter 미노출.
 *
 * 불변식:
 * <ul>
 *   <li>(userId, idempotencyKey) UNIQUE - DB constraint</li>
 *   <li>couponCode 는 status == SUCCEEDED 일 때만 non-null</li>
 *   <li>failureReason 은 status 가 FAILED 또는 REJECTED 일 때만 non-null</li>
 *   <li>상태 전이는 IssueRequestStatus 의 transition graph 만 허용</li>
 * </ul>
 */
@Getter
public class IssueRequest {

    private Long id;
    private String requestId;
    private Long userId;
    private Long eventId;
    private String idempotencyKey;
    private IssueRequestStatus status;
    private CouponCode couponCode;
    private String failureReason;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    private IssueRequest() {
    }

    /**
     * 새 발급 요청 생성. 상태는 RECEIVED, requestId 는 UUID 자동 생성.
     * createdAt/updatedAt 은 호출자가 주입한 now 로 set (테스트 가능성 + 영속화 전 시각 참조 안전).
     */
    public static IssueRequest received(Long userId, Long eventId, String idempotencyKey, Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(now, "now");

        IssueRequest req = new IssueRequest();
        req.requestId = UUID.randomUUID().toString();
        req.userId = userId;
        req.eventId = eventId;
        req.idempotencyKey = idempotencyKey;
        req.status = IssueRequestStatus.RECEIVED;
        req.createdAt = now;
        req.updatedAt = now;
        return req;
    }

    /**
     * Mapper 가 JpaEntity → 도메인 변환 시 사용. 외부에서는 호출 금지.
     */
    public static IssueRequest reconstitute(
        Long id,
        String requestId,
        Long userId,
        Long eventId,
        String idempotencyKey,
        IssueRequestStatus status,
        CouponCode couponCode,
        String failureReason,
        Long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        IssueRequest req = new IssueRequest();
        req.id = id;
        req.requestId = requestId;
        req.userId = userId;
        req.eventId = eventId;
        req.idempotencyKey = idempotencyKey;
        req.status = status;
        req.couponCode = couponCode;
        req.failureReason = failureReason;
        req.version = version;
        req.createdAt = createdAt;
        req.updatedAt = updatedAt;
        return req;
    }

    public void markForwarded() {
        transition(IssueRequestStatus.FORWARDED);
    }

    public void markSucceeded(CouponCode couponCode) {
        Objects.requireNonNull(couponCode, "couponCode");
        transition(IssueRequestStatus.SUCCEEDED);
        this.couponCode = couponCode;
    }

    public void markFailed(String reason) {
        Objects.requireNonNull(reason, "failureReason");
        transition(IssueRequestStatus.FAILED);
        this.failureReason = reason;
    }

    /**
     * Idempotency conflict / Rate limit / Validation 등으로 즉시 거절.
     * RECEIVED 상태에서만 호출 가능 — FORWARDED 이후엔 이미 B 에 위임된 상태이므로 거절 불가.
     */
    public void reject(String reason) {
        Objects.requireNonNull(reason, "rejectReason");
        transition(IssueRequestStatus.REJECTED);
        this.failureReason = reason;
    }

    private void transition(IssueRequestStatus next) {
        if (!this.status.canTransitionTo(next)) {
            throw new IllegalStateException(
                "invalid IssueRequest transition: %s → %s (requestId=%s)"
                    .formatted(this.status, next, this.requestId));
        }
        this.status = next;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IssueRequest other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
