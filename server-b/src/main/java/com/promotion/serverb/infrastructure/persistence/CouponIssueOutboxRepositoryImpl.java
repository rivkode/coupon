package com.promotion.serverb.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.common.coupon.CouponCode;
import com.promotion.serverb.domain.CouponIssueOutbox;
import com.promotion.serverb.domain.CouponIssueOutboxRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
class CouponIssueOutboxRepositoryImpl implements CouponIssueOutboxRepository {

    private final CouponIssueOutboxJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    CouponIssueOutboxRepositoryImpl(
        CouponIssueOutboxJpaRepository jpaRepository,
        ObjectMapper objectMapper
    ) {
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public CouponIssueOutbox save(CouponIssueOutbox outbox) {
        CouponIssueOutboxJpaEntity entity = CouponIssueOutboxMapper.toEntity(outbox, objectMapper);
        CouponIssueOutboxJpaEntity saved = jpaRepository.save(entity);
        return CouponIssueOutboxMapper.toDomain(saved, objectMapper);
    }

    @Override
    public Optional<CouponIssueOutbox> findByCouponCode(CouponCode couponCode) {
        return jpaRepository.findByCouponCode(couponCode.value())
            .map(e -> CouponIssueOutboxMapper.toDomain(e, objectMapper));
    }

    @Override
    public Optional<CouponIssueOutbox> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey)
            .map(e -> CouponIssueOutboxMapper.toDomain(e, objectMapper));
    }

    @Override
    public List<CouponIssueOutbox> findUnpublished(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        return jpaRepository.findUnpublished(PageRequest.of(0, limit)).stream()
            .map(e -> CouponIssueOutboxMapper.toDomain(e, objectMapper))
            .toList();
    }

    @Override
    public long count() {
        return jpaRepository.count();
    }

    @Override
    public void deleteAll() {
        jpaRepository.deleteAllInBatch();
    }
}
