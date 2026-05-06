package com.promotion.common.coupon;

/**
 * Server B 의 발급 결과 코드. Server A 의 IssueRequestStatus 와 다름 (혼동 주의).
 *
 * <ul>
 *   <li>{@link #ISSUED} — 정상 발급, couponCode non-null</li>
 *   <li>{@link #SOLD_OUT} — 재고 소진</li>
 *   <li>{@link #ALREADY_ISSUED} — 같은 idempotency-key 로 이미 발급된 코드 재반환</li>
 *   <li>{@link #INTERNAL_ERROR} — Server B 내부 오류 또는 Circuit Breaker fallback</li>
 * </ul>
 */
public enum IssueStatus {
    ISSUED,
    SOLD_OUT,
    ALREADY_ISSUED,
    INTERNAL_ERROR
}
