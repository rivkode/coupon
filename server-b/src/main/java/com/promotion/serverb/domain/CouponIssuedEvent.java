package com.promotion.serverb.domain;

import com.promotion.common.coupon.CouponCode;
import java.time.Instant;
import java.util.Objects;

/**
 * 도메인 사건 — 쿠폰 1장이 발급되었음.
 *
 * <p>{@link CouponIssueOutbox} Aggregate 의 payload. Server C 의 Kafka consumer 가 본 record 의
 * JSON 표현을 받아 영구 저장한다 (CLAUDE.md ADR-002).
 *
 * <p>JSON 직렬화 형식은 도메인이 모른다 — infrastructure (Mapper) 가 영속 시점에 변환한다.
 * 도메인 단위 테스트는 직렬화에 의존하지 않고 record 그대로 비교 가능.
 */
public record CouponIssuedEvent(
    long eventId,
    long userId,
    CouponCode couponCode,
    String idempotencyKey,
    Instant issuedAt
) {

    public CouponIssuedEvent {
        if (eventId <= 0) throw new IllegalArgumentException("eventId must be positive");
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        Objects.requireNonNull(couponCode, "couponCode");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey blank");
        Objects.requireNonNull(issuedAt, "issuedAt");
    }
}
