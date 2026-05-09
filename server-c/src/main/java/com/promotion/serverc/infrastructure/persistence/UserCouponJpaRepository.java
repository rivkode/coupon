package com.promotion.serverc.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserCouponJpaRepository extends JpaRepository<UserCouponJpaEntity, Long> {

    Optional<UserCouponJpaEntity> findByCode(String code);

    Optional<UserCouponJpaEntity> findByUserIdAndCouponTypeId(Long userId, Long couponTypeId);

    boolean existsByUserIdAndCouponTypeId(Long userId, Long couponTypeId);
}
