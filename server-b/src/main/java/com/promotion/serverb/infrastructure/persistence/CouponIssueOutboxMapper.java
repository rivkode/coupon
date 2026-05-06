package com.promotion.serverb.infrastructure.persistence;

import com.promotion.common.coupon.CouponCode;
import com.promotion.serverb.domain.CouponIssueOutbox;

final class CouponIssueOutboxMapper {

    private CouponIssueOutboxMapper() {
    }

    static CouponIssueOutbox toDomain(CouponIssueOutboxJpaEntity entity) {
        return CouponIssueOutbox.reconstitute(
            entity.getId(),
            new CouponCode(entity.getCouponCode()),
            entity.getIdempotencyKey(),
            entity.getPayload(),
            entity.isPublished(),
            entity.getPublishedAt(),
            entity.getVersion(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    static CouponIssueOutboxJpaEntity toEntity(CouponIssueOutbox domain) {
        return CouponIssueOutboxJpaEntity.builder()
            .id(domain.getId())
            .couponCode(domain.getCouponCode().value())
            .idempotencyKey(domain.getIdempotencyKey())
            .payload(domain.getPayload())
            .published(domain.isPublished())
            .publishedAt(domain.getPublishedAt())
            .version(domain.getVersion())
            .createdAt(domain.getCreatedAt())
            .updatedAt(domain.getUpdatedAt())
            .build();
    }
}
