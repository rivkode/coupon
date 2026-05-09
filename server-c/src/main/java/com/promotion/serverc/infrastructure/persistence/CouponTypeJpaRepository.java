package com.promotion.serverc.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponTypeJpaRepository extends JpaRepository<CouponTypeJpaEntity, Long> {
}
