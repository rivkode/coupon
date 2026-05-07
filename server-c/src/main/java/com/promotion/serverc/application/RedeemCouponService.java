package com.promotion.serverc.application;

import com.promotion.serverc.domain.Coupon;
import com.promotion.serverc.domain.CouponRepository;
import com.promotion.serverc.domain.exception.CouponNotFoundException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 사용(Redeem) application service (CLAUDE.md §5.3, ADR-007).
 *
 * <p>흐름:
 * <ol>
 *   <li>code 로 조회 → 없으면 {@link CouponNotFoundException} (404).</li>
 *   <li>소유권 검증 — 다른 user 의 쿠폰이면 {@link CouponNotFoundException} 으로 마스킹 (404).
 *       코드 존재 여부를 누설하지 않는다.</li>
 *   <li>이미 사용된 쿠폰:
 *     <ul>
 *       <li>같은 user 의 재호출 → 200 + 기존 redeemedAt (멱등).</li>
 *       <li>다른 user → 위 (2) 에서 이미 거름.</li>
 *     </ul>
 *   </li>
 *   <li>{@link Coupon#redeem(Instant)} 호출 + save → 낙관적 락이 동시 호출을 한 명으로 제한.</li>
 * </ol>
 *
 * <p>낙관락 실패는 본 service 가 catch 하지 않는다 — {@code OptimisticLockingFailureException} 이
 * GlobalExceptionHandler 까지 propagate 되어 409 RACE_RETRY 로 매핑된다. 클라이언트가 재시도하면
 * (3) 의 멱등 분기로 자연 흡수.
 *
 * <p>Idempotency-Key 헤더는 trace/log 용 — 별도 캐시 없이 redeem 의 본질적 멱등성으로 충분
 * (CLAUDE.md ADR-004, README 트레이드오프).
 */
@Service
@RequiredArgsConstructor
public class RedeemCouponService {

    private static final Logger log = LoggerFactory.getLogger(RedeemCouponService.class);

    private final CouponRepository couponRepository;

    @Transactional
    public RedeemResult redeem(RedeemCommand command) {
        Coupon coupon = couponRepository.findByCode(command.code())
            .orElseThrow(() -> new CouponNotFoundException(
                "coupon not found: code=" + command.code().value()));

        if (!coupon.getUserId().equals(command.userId())) {
            // 소유권 위반 — 코드 존재 여부 누설 방지를 위해 404 로 마스킹.
            log.warn("redeem ownership mismatch: code={}, owner={}, requester={}",
                command.code().value(), coupon.getUserId(), command.userId());
            throw new CouponNotFoundException(
                "coupon not found: code=" + command.code().value());
        }

        if (coupon.isUsed()) {
            // 같은 user 의 재호출 — 멱등하게 기존 결과 반환 (200 + newlyRedeemed=false).
            // 본 분기가 도메인의 redeem() IllegalStateException 가드보다 먼저 가로챈다 — 도메인 가드는
            // service 가 락 race 등으로 실패 시 마지막 안전망으로만 도달한다.
            log.info("redeem idempotent replay: code={}, userId={}, idem={}",
                command.code().value(), command.userId(), command.idempotencyKey());
            return RedeemResult.alreadyRedeemed(coupon.getCode(), coupon.getUserId(), coupon.getUsedAt());
        }

        // 본 분기 이후 도달 — coupon.redeem() 의 IllegalStateException 은 service 단 멱등 분기를 통과한
        // 뒤에만 발생 가능 (논리상 도달 불가에 가깝지만 도메인 가드는 유지하여 invariant 보호).
        coupon.redeem(Instant.now());
        Coupon saved = couponRepository.save(coupon);
        log.info("redeem succeeded: code={}, userId={}, idem={}",
            command.code().value(), command.userId(), command.idempotencyKey());
        return RedeemResult.succeeded(saved.getCode(), saved.getUserId(), saved.getUsedAt());
    }
}
