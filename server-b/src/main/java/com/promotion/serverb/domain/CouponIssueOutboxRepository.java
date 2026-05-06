package com.promotion.serverb.domain;

import com.promotion.common.coupon.CouponCode;
import java.util.List;
import java.util.Optional;

/**
 * Server B 의 Outbox 저장소. 구현은 infrastructure/persistence 에 위치.
 * Spring Data JpaRepository 를 application 레이어에 직접 노출하지 않기 위한 분리.
 */
public interface CouponIssueOutboxRepository {

    /**
     * Outbox 행을 저장. 같은 idempotency_key 또는 coupon_code 가 이미 있으면 DB UNIQUE 제약 위반으로 예외.
     */
    CouponIssueOutbox save(CouponIssueOutbox outbox);

    Optional<CouponIssueOutbox> findByCouponCode(CouponCode couponCode);

    Optional<CouponIssueOutbox> findByIdempotencyKey(String idempotencyKey);

    /**
     * poller 가 미발행 행을 created_at 오름차순으로 batch 조회.
     * idx_outbox_unpublished (published, created_at) 인덱스를 활용.
     */
    List<CouponIssueOutbox> findUnpublished(int limit);

    /** Outbox 전체 행 수 — 모니터링 / 통합 테스트 검증 용도. */
    long count();

    /** Outbox 전체 삭제 — 통합 테스트 cleanup 용도. 운영 호출 금지. */
    void deleteAll();
}
