package com.promotion.serverb.infrastructure.persistence;

import com.promotion.common.coupon.CouponCode;
import com.promotion.serverb.domain.CouponIssueOutbox;
import com.promotion.serverb.domain.CouponIssueOutboxRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class CouponIssueOutboxRepositoryImpl implements CouponIssueOutboxRepository {

    private final CouponIssueOutboxJpaRepository jpaRepository;

    @Override
    public CouponIssueOutbox save(CouponIssueOutbox outbox) {
        CouponIssueOutboxJpaEntity entity = CouponIssueOutboxMapper.toEntity(outbox);
        CouponIssueOutboxJpaEntity saved = jpaRepository.save(entity);
        return CouponIssueOutboxMapper.toDomain(saved);
    }

    @Override
    public Optional<CouponIssueOutbox> findByCouponCode(CouponCode couponCode) {
        return jpaRepository.findByCouponCode(couponCode.value())
            .map(CouponIssueOutboxMapper::toDomain);
    }

    @Override
    public Optional<CouponIssueOutbox> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey)
            .map(CouponIssueOutboxMapper::toDomain);
    }

    @Override
    public List<CouponIssueOutbox> findUnpublished(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        return jpaRepository.findUnpublished(PageRequest.of(0, limit)).stream()
            .map(CouponIssueOutboxMapper::toDomain)
            .toList();
    }
}
