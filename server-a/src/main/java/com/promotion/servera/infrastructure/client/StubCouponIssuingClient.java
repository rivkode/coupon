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
 * RestClientCouponIssuingClient 가 대신 주입된다.
 *
 * <p>응답 시뮬레이션 (idempotency-key prefix 기반):
 * <ul>
 *   <li>{@code sold-out-*} — {@link IssueResult#soldOut()} 반환. e2e SOLD_OUT 시나리오 검증용.</li>
 *   <li>그 외 — 새 CouponCode 를 생성해 {@link IssueResult#issued(CouponCode)} 반환.</li>
 * </ul>
 *
 * <p>{@code ALREADY_ISSUED} 는 server-a 의 IdempotencyFilter 가 같은 키 두 번째 호출을 캐시 hit 으로
 * 차단하므로 Stub 까지 도달할 일이 거의 없어 시뮬레이션 미포함.
 *
 * <p><b>주의</b>: SOLD_OUT trigger 도 한 번만 동작 — 같은 idempotency-key 재호출은 IdempotencyFilter
 * 가 첫 응답(200 + status=FAILED)을 캐시 hit 으로 반환하므로 Stub 까지 도달하지 않음. k6
 * day2-03 시나리오는 매 iteration 다른 trigger key 를 생성한다.
 */
@Component
@Profile("local")
public class StubCouponIssuingClient implements CouponIssuingClient {

    private static final Logger log = LoggerFactory.getLogger(StubCouponIssuingClient.class);

    /** k6 day2-03 시나리오에서 SOLD_OUT 케이스를 강제 발생시키는 트리거 prefix. */
    private static final String SOLD_OUT_TRIGGER_PREFIX = "sold-out-";

    @Override
    public IssueResult issue(Long userId, Long eventId, String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKey.startsWith(SOLD_OUT_TRIGGER_PREFIX)) {
            log.info("[stub] coupon SOLD_OUT (trigger key) userId={} eventId={} idem={}",
                userId, eventId, idempotencyKey);
            return IssueResult.soldOut();
        }
        CouponCode code = CouponCode.generate();
        log.info("[stub] coupon issued userId={} eventId={} couponCode={}", userId, eventId, code.value());
        return IssueResult.issued(code);
    }
}
