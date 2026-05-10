package com.promotion.serverc.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserCouponJpaRepository extends JpaRepository<UserCouponJpaEntity, Long> {

    Optional<UserCouponJpaEntity> findByCode(String code);

    Optional<UserCouponJpaEntity> findByUserIdAndCouponTypeId(Long userId, Long couponTypeId);

    boolean existsByUserIdAndCouponTypeId(Long userId, Long couponTypeId);

    // idx_user_coupon_user 인덱스로 조회. 사용자당 이벤트 수 상한이 100 (CLAUDE.md §1) 이라
    // 페이지네이션 없이 ORDER BY 만으로 충분.
    List<UserCouponJpaEntity> findAllByUserIdOrderByIssuedAtDesc(Long userId);
}
