package com.promotion.serverc.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface CouponJpaRepository extends JpaRepository<CouponJpaEntity, Long> {

    Optional<CouponJpaEntity> findByCode(String code);

    Optional<CouponJpaEntity> findByIdempotencyKey(String idempotencyKey);

    List<CouponJpaEntity> findByUserId(Long userId);
}
