package com.promotion.servera.infrastructure.persistence;

import com.promotion.common.coupon.CouponCode;
import com.promotion.servera.domain.IssueRequest;

final class IssueRequestMapper {

    private IssueRequestMapper() {
    }

    static IssueRequest toDomain(IssueRequestJpaEntity entity) {
        CouponCode code = entity.getCouponCode() == null ? null : new CouponCode(entity.getCouponCode());
        return IssueRequest.reconstitute(
            entity.getId(),
            entity.getRequestId(),
            entity.getUserId(),
            entity.getEventId(),
            entity.getIdempotencyKey(),
            entity.getStatus(),
            code,
            entity.getFailureReason(),
            entity.getVersion(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    static IssueRequestJpaEntity toEntity(IssueRequest domain) {
        return IssueRequestJpaEntity.builder()
            .id(domain.getId())
            .requestId(domain.getRequestId())
            .userId(domain.getUserId())
            .eventId(domain.getEventId())
            .idempotencyKey(domain.getIdempotencyKey())
            .status(domain.getStatus())
            .couponCode(domain.getCouponCode() == null ? null : domain.getCouponCode().value())
            .failureReason(domain.getFailureReason())
            .version(domain.getVersion())
            .createdAt(domain.getCreatedAt())
            .updatedAt(domain.getUpdatedAt())
            .build();
    }
}
