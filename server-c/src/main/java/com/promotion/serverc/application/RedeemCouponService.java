package com.promotion.serverc.application;

import com.promotion.serverc.domain.exception.CouponNotFoundException;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaEntity;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Redeem application service (CLAUDE.md ADR-007).
 *
 * <ol>
 *   <li>code 로 user_coupon 조회 → 없으면 404</li>
 *   <li>소유권 검증 (다른 user → 404 마스킹)</li>
 *   <li>이미 USED → 같은 user 재호출은 멱등 200, redeemedAt 그대로</li>
 *   <li>UserCouponJpaEntity#markUsed → @Version 낙관락으로 동시 호출 1 명만 성공</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class RedeemCouponService {

    private static final Logger log = LoggerFactory.getLogger(RedeemCouponService.class);

    private final UserCouponJpaRepository repository;

    @Transactional
    public RedeemResult redeem(RedeemCommand command) {
        UserCouponJpaEntity uc = repository.findByCode(command.code())
                .orElseThrow(() -> new CouponNotFoundException("coupon not found: code=" + command.code()));

        if (uc.getUserId() != command.userId()) {
            log.warn("redeem ownership mismatch: code={}, owner={}, requester={}",
                    command.code(), uc.getUserId(), command.userId());
            throw new CouponNotFoundException("coupon not found: code=" + command.code());
        }

        if (uc.getUsedAt() != null) {
            log.info("redeem idempotent replay: code={}, userId={}", command.code(), command.userId());
            return RedeemResult.alreadyRedeemed(uc.getCode(), uc.getUserId(), uc.getUsedAt());
        }

        LocalDateTime now = LocalDateTime.now();
        if (!uc.markUsed(now)) {
            throw new IllegalStateException("coupon is not redeemable: status=" + uc.getStatus());
        }
        UserCouponJpaEntity saved = repository.save(uc);
        log.info("redeem succeeded: code={}, userId={}", command.code(), command.userId());
        return RedeemResult.succeeded(saved.getCode(), saved.getUserId(), saved.getUsedAt());
    }
}
