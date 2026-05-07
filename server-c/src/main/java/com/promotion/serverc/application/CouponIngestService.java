package com.promotion.serverc.application;

import com.promotion.common.coupon.CouponCode;
import com.promotion.common.coupon.CouponIssuedEventPayload;
import com.promotion.serverc.domain.Coupon;
import com.promotion.serverc.domain.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server B 의 Outbox poller 가 발행한 {@code coupon.issued} 메시지를 영구 저장하는 application service.
 *
 * <p>멱등성 보장 (CLAUDE.md ADR-002, ADR-004):
 * <ul>
 *   <li>Producer (Server B) 는 at-least-once — poller 가 markPublished 직전 크래시 시 재발행 가능.</li>
 *   <li>Consumer (Server C) 는 {@code (user_id, idempotency_key)} 와 {@code code} UNIQUE constraint
 *       로 중복 INSERT 를 거부 → 의미적 exactly-once.</li>
 * </ul>
 *
 * <p>본 service 는 {@link DataIntegrityViolationException} 을 catch 하지 않는다 —
 * {@code @Transactional} 안에서 RuntimeException 을 catch 하면 트랜잭션이 rollback-only 로 마크돼
 * commit 시점에 {@code UnexpectedRollbackException} 이 발생하기 때문. UNIQUE 위반 흡수는 호출자
 * (listener) 가 트랜잭션 경계 밖에서 처리한다.
 */
@Service
@RequiredArgsConstructor
public class CouponIngestService {

    private final CouponRepository couponRepository;

    @Transactional
    public void ingest(CouponIssuedEventPayload payload) {
        Coupon coupon = Coupon.issue(
            new CouponCode(payload.couponCode()),
            payload.userId(),
            payload.eventId(),
            payload.idempotencyKey(),
            payload.issuedAt()
        );
        couponRepository.save(coupon);
    }
}
