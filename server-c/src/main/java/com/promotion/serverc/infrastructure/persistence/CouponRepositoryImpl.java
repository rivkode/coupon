package com.promotion.serverc.infrastructure.persistence;

import com.promotion.common.coupon.CouponCode;
import com.promotion.serverc.domain.Coupon;
import com.promotion.serverc.domain.CouponRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class CouponRepositoryImpl implements CouponRepository {

    private final CouponJpaRepository jpaRepository;

    @Override
    public Coupon save(Coupon coupon) {
        CouponJpaEntity entity = CouponMapper.toEntity(coupon);
        CouponJpaEntity saved = jpaRepository.save(entity);
        return CouponMapper.toDomain(saved);
    }

    @Override
    public Optional<Coupon> findByCode(CouponCode code) {
        return jpaRepository.findByCode(code.value()).map(CouponMapper::toDomain);
    }

    @Override
    public Optional<Coupon> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey).map(CouponMapper::toDomain);
    }

    @Override
    public List<Coupon> findByUserId(Long userId) {
        return jpaRepository.findByUserId(userId).stream()
            .map(CouponMapper::toDomain)
            .toList();
    }
}
