package com.promotion.serverb.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CouponIssueOutboxJpaRepository extends JpaRepository<CouponIssueOutboxJpaEntity, Long> {

    Optional<CouponIssueOutboxJpaEntity> findByCouponCode(String couponCode);

    Optional<CouponIssueOutboxJpaEntity> findByIdempotencyKey(String idempotencyKey);

    // idx_outbox_unpublished (published, created_at) 활용. published=false 만 created_at 오름차순.
    @Query("select o from CouponIssueOutboxJpaEntity o where o.published = false order by o.createdAt asc")
    List<CouponIssueOutboxJpaEntity> findUnpublished(Pageable pageable);

    /**
     * MySQL 8.0+ 의 {@code FOR UPDATE SKIP LOCKED} 를 native query 로 지정.
     * Hibernate {@code @Lock(PESSIMISTIC_WRITE)} 는 SKIP LOCKED 를 표준 표현하지 못하므로 native.
     *
     * <p>호출은 반드시 트랜잭션 안에서. lock 은 트랜잭션 종료 시 해제.
     */
    @Query(
        value = """
            SELECT * FROM coupon_issue_outbox
             WHERE published = false
             ORDER BY created_at ASC
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """,
        nativeQuery = true
    )
    List<CouponIssueOutboxJpaEntity> findUnpublishedForUpdate(@Param("limit") int limit);
}
