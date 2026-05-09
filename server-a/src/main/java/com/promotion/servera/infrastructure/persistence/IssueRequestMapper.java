package com.promotion.servera.infrastructure.persistence;

import com.promotion.servera.domain.IssueRequest;

final class IssueRequestMapper {

    private IssueRequestMapper() {}

    static IssueRequestJpaEntity toEntity(IssueRequest req) {
        return new IssueRequestJpaEntity(
                req.getId(),
                req.getRequestId(),
                req.getUserId(),
                req.getEventId(),
                req.getCouponTypeId(),
                req.getStatus(),
                req.getCreatedAt()
        );
    }

    static IssueRequest toDomain(IssueRequestJpaEntity e) {
        return IssueRequest.reconstitute(
                e.getId(),
                e.getRequestId(),
                e.getUserId(),
                e.getEventId(),
                e.getCouponTypeId(),
                e.getStatus(),
                e.getCreatedAt()
        );
    }
}
