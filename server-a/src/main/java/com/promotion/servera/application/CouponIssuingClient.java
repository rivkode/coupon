package com.promotion.servera.application;

import com.promotion.common.coupon.IssueResult;

/**
 * Server A → Server B 발급 호출 포트.
 *
 * <p>Application 레이어에 위치하는 이유: Service 가 의존하는 추상화이며 구현(Stub / RestClient)은
 * infrastructure 에서 profile 별로 주입된다.
 *
 * <p>구현체:
 * <ul>
 *   <li>{@code StubCouponIssuingClient} — {@code @Profile("local")}, 항상 ISSUED 반환</li>
 *   <li>{@code RestClientCouponIssuingClient} — {@code @Profile("!local")}, Phase 8 에서 추가</li>
 * </ul>
 */
public interface CouponIssuingClient {

    /**
     * Server B 의 재고 차감 + 쿠폰 코드 생성을 호출.
     *
     * @param userId         발급 대상 사용자 (Header X-User-Id)
     * @param eventId        이벤트 식별자
     * @param idempotencyKey 멱등성 키 (Header Idempotency-Key)
     * @return 발급 결과 (ISSUED / ALREADY_ISSUED / SOLD_OUT / INTERNAL_ERROR)
     */
    IssueResult issue(Long userId, Long eventId, String idempotencyKey);
}
