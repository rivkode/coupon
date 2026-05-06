package com.promotion.serverc.domain;

import com.promotion.common.coupon.CouponCode;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;

/**
 * 쿠폰 영구 저장 도메인 객체 (Server C Aggregate Root). Source of truth for issued coupons.
 *
 * 불변식:
 * <ul>
 *   <li>code UNIQUE (DB constraint)</li>
 *   <li>idempotencyKey UNIQUE — 멱등성 최종 보루 (CLAUDE.md ADR-004)</li>
 *   <li>usedAt 은 한 번 set 후 변경 불가 (낙관적 락 + redeem 검증)</li>
 *   <li>발행(issuedAt)은 생성 시점에 set 되고 변경 불가</li>
 * </ul>
 *
 * 동시성: redeem 동시 호출 시 @Version 낙관적 락 (CLAUDE.md ADR-007).
 */
@Getter
public class Coupon {

    private Long id;
    private CouponCode code;
    private Long userId;
    private Long eventId;
    private String idempotencyKey;
    private Instant issuedAt;
    private Instant usedAt;
    private Long version;

    private Coupon() {
    }

    /**
     * Server B 발급 이벤트 consume 시 호출 (Day 3 작업).
     */
    public static Coupon issue(CouponCode code, Long userId, Long eventId, String idempotencyKey, Instant issuedAt) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(issuedAt, "issuedAt");

        Coupon coupon = new Coupon();
        coupon.code = code;
        coupon.userId = userId;
        coupon.eventId = eventId;
        coupon.idempotencyKey = idempotencyKey;
        coupon.issuedAt = issuedAt;
        return coupon;
    }

    public static Coupon reconstitute(
        Long id,
        CouponCode code,
        Long userId,
        Long eventId,
        String idempotencyKey,
        Instant issuedAt,
        Instant usedAt,
        Long version
    ) {
        Coupon coupon = new Coupon();
        coupon.id = id;
        coupon.code = code;
        coupon.userId = userId;
        coupon.eventId = eventId;
        coupon.idempotencyKey = idempotencyKey;
        coupon.issuedAt = issuedAt;
        coupon.usedAt = usedAt;
        coupon.version = version;
        return coupon;
    }

    /**
     * 쿠폰 사용. 이미 사용된 경우 {@link IllegalStateException}.
     * 동시성은 호출자가 @Version 낙관적 락으로 보호 (Day 3 작업).
     */
    public void redeem(Instant now) {
        Objects.requireNonNull(now, "now");
        if (usedAt != null) {
            throw new IllegalStateException(
                "coupon already redeemed at %s (code=%s)".formatted(usedAt, code.value()));
        }
        this.usedAt = now;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Coupon other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
