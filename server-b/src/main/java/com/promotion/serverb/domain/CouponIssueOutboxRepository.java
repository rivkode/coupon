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

    /**
     * poller 가 미발행 행을 row lock + SKIP LOCKED 로 batch 조회.
     *
     * <p>MySQL 8.0+ 의 {@code FOR UPDATE SKIP LOCKED} 로 동시에 도는 두 SELECT 가 같은 행을
     * 동시에 잡지 않게 함. 단, **lock 은 호출 트랜잭션 종료 시 해제**되므로 publish/markPublished
     * 를 별도 트랜잭션으로 분리한 OutboxPoller 의 흐름에서는 SKIP LOCKED 가 "동시 SELECT 직렬화"
     * 정도만 보장. 진짜 동시 발행 방어 (lock 해제 후 다른 인스턴스가 같은 행을 다시 SELECT
     * 하는 경우) 는 Server C 의 UNIQUE constraint 가 권위 (CLAUDE.md ADR-004 / PR #15).
     *
     * <p>본 메서드를 트랜잭션 안에서 호출해야 native lock 이 의미가 있다. Kafka publish 는
     * 트랜잭션 밖에서 수행 (CLAUDE.md §10 안티패턴 회피). OutboxPoller 가 두 단계로 분리한다.
     */
    List<CouponIssueOutbox> findUnpublishedForUpdate(int limit);

    /** Outbox 전체 행 수 — 모니터링 / 통합 테스트 검증 용도. */
    long count();

    /** Outbox 전체 삭제 — 통합 테스트 cleanup 용도. 운영 호출 금지. */
    void deleteAll();
}
