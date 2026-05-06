package com.promotion.servera.infrastructure.client;

import com.promotion.common.coupon.CouponCode;
import com.promotion.common.coupon.IssueResult;
import com.promotion.servera.application.CouponIssuingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Server B 부재 상태에서 Server A 단독 동작을 위한 stub.
 *
 * <p>{@code @Profile("local")} 로만 활성화되며 production / dev profile 에서는
 * RestClientCouponIssuingClient (Phase 8) 가 대신 주입된다.
 *
 * <p>현재는 항상 새 CouponCode 를 생성해 ISSUED 로 응답한다. SOLD_OUT / INTERNAL_ERROR
 * 시뮬레이션은 후속 PR 에서 환경변수 hook 으로 추가 예정.
 */
@Component
@Profile("local")
public class StubCouponIssuingClient implements CouponIssuingClient {

    private static final Logger log = LoggerFactory.getLogger(StubCouponIssuingClient.class);

    @Override
    public IssueResult issue(Long userId, Long eventId, String idempotencyKey) {
        CouponCode code = CouponCode.generate();
        log.info("[stub] coupon issued userId={} eventId={} couponCode={}", userId, eventId, code.value());
        return IssueResult.issued(code);
    }
}
