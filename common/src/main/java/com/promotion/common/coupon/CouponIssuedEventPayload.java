package com.promotion.common.coupon;

import java.time.Instant;
import java.util.Objects;

/**
 * Server B → Server C 의 producer-consumer 계약 (Kafka {@code coupon.issued} 토픽 value
 * + Server B Outbox 의 payload 컬럼). 평탄화된 외부 계약 — couponCode 는 String 으로 직렬화하여
 * consumer 가 nested object 를 다루지 않게 한다.
 *
 * <p>도메인 객체로의 변환은 의도적으로 본 record 에 두지 않는다. server-b 는 {@code CouponCode} VO
 * 와 {@code CouponIssuedEvent} 도메인 record 를 갖고 있고, server-c 는 {@code Coupon} aggregate 만
 * 갖고 있어 양측의 도메인 모델이 다르다. 본 record 는 "선 위에서 흐르는 메시지" 에 충실한다.
 */
public record CouponIssuedEventPayload(
    long eventId,
    long userId,
    String couponCode,
    String idempotencyKey,
    Instant issuedAt
) {

    /** {@link CouponCode} VO 와 동일한 길이 제약 — 잘못된 코드의 메시지를 역직렬화 시점에 거른다. */
    private static final int COUPON_CODE_LENGTH = 12;

    public CouponIssuedEventPayload {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive but was " + eventId);
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive but was " + userId);
        }
        Objects.requireNonNull(couponCode, "couponCode");
        if (couponCode.length() != COUPON_CODE_LENGTH) {
            // poison pill — JSON 역직렬화 단계에서 ValueInstantiationException 으로 wrap 되어
            // listener 의 JsonProcessingException catch 분기로 흡수된다 (재처리해도 항상 실패).
            throw new IllegalArgumentException(
                "couponCode length must be %d but was %d".formatted(COUPON_CODE_LENGTH, couponCode.length()));
        }
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(issuedAt, "issuedAt");
    }
}
