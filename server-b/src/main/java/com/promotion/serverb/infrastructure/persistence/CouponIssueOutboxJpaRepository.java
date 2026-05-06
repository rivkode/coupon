package com.promotion.serverb.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CouponIssueOutboxJpaRepository extends JpaRepository<CouponIssueOutboxJpaEntity, Long> {

    Optional<CouponIssueOutboxJpaEntity> findByCouponCode(String couponCode);

    Optional<CouponIssueOutboxJpaEntity> findByIdempotencyKey(String idempotencyKey);

    // idx_outbox_unpublished (published, created_at) 활용. published=false 만 created_at 오름차순.
    @Query("select o from CouponIssueOutboxJpaEntity o where o.published = false order by o.createdAt asc")
    List<CouponIssueOutboxJpaEntity> findUnpublished(Pageable pageable);
}
