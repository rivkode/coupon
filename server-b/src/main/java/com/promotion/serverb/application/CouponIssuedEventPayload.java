package com.promotion.serverb.application;

import com.promotion.common.coupon.CouponCode;
import com.promotion.serverb.domain.CouponIssuedEvent;
import java.time.Instant;

/**
 * Outbox 의 JSON payload 컬럼 + Kafka 메시지 value 의 외부 계약.
 *
 * <p>도메인 record({@link CouponIssuedEvent}) 는 {@link CouponCode} 라는 VO 를 들고 있어
 * Jackson 으로 그대로 직렬화하면 {@code "couponCode":{"value":"..."}} 형태의 nested 가 되어
 * consumer 가 다루기 불편하다. 본 record 는 평탄화된 외부 계약 (couponCode 를 String 으로).
 *
 * <p>본 형식이 일관되면 {@code persistence/CouponIssueOutboxMapper} 와
 * {@code infrastructure/kafka/KafkaCouponIssuedEventPublisher} 가 같은 직렬화 형태를 공유 →
 * Outbox 행의 payload 컬럼을 (재변환 없이) 그대로 Kafka 발행해도 정합.
 */
public record CouponIssuedEventPayload(
    long eventId,
    long userId,
    String couponCode,
    String idempotencyKey,
    Instant issuedAt
) {

    public static CouponIssuedEventPayload of(CouponIssuedEvent event) {
        return new CouponIssuedEventPayload(
            event.eventId(),
            event.userId(),
            event.couponCode().value(),
            event.idempotencyKey(),
            event.issuedAt()
        );
    }

    public CouponIssuedEvent toDomain() {
        return new CouponIssuedEvent(
            eventId,
            userId,
            new CouponCode(couponCode),
            idempotencyKey,
            issuedAt
        );
    }
}
