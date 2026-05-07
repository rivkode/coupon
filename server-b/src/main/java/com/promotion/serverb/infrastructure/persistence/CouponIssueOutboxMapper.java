package com.promotion.serverb.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.common.coupon.CouponCode;
import com.promotion.common.coupon.CouponIssuedEventPayload;
import com.promotion.serverb.domain.CouponIssueOutbox;
import com.promotion.serverb.domain.CouponIssuedEvent;

/**
 * Domain ↔ JpaEntity 양방향 변환. JSON 직렬화도 본 mapper 가 담당 — 도메인은 형식을 모른다.
 *
 * <p>변환 시점에만 ObjectMapper 가 필요하므로 호출자(RepositoryImpl)가 빈을 보유해 인자로 전달.
 * Mapper 자체는 static utility 로 두어 server-a 의 Mapper 패턴과 일관 유지.
 */
final class CouponIssueOutboxMapper {

    private CouponIssueOutboxMapper() {
    }

    static CouponIssueOutbox toDomain(CouponIssueOutboxJpaEntity entity, ObjectMapper objectMapper) {
        CouponIssuedEvent event = deserialize(entity.getPayload(), objectMapper);
        return CouponIssueOutbox.reconstitute(
            entity.getId(),
            event,
            entity.isPublished(),
            entity.getPublishedAt(),
            entity.getVersion(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    static CouponIssueOutboxJpaEntity toEntity(CouponIssueOutbox domain, ObjectMapper objectMapper) {
        return CouponIssueOutboxJpaEntity.builder()
            .id(domain.getId())
            .couponCode(domain.couponCode().value())
            .userId(domain.getEvent().userId())
            .idempotencyKey(domain.idempotencyKey())
            .payload(serialize(domain.getEvent(), objectMapper))
            .published(domain.isPublished())
            .publishedAt(domain.getPublishedAt())
            .version(domain.getVersion())
            .createdAt(domain.getCreatedAt())
            .updatedAt(domain.getUpdatedAt())
            .build();
    }

    private static String serialize(CouponIssuedEvent event, ObjectMapper objectMapper) {
        CouponIssuedEventPayload payload = new CouponIssuedEventPayload(
            event.eventId(),
            event.userId(),
            event.couponCode().value(),
            event.idempotencyKey(),
            event.issuedAt()
        );
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // record 직렬화는 정상적으론 실패 불가 — 발생 시 시스템 결함.
            throw new IllegalStateException("outbox payload serialization failed", e);
        }
    }

    private static CouponIssuedEvent deserialize(String json, ObjectMapper objectMapper) {
        try {
            CouponIssuedEventPayload payload = objectMapper.readValue(json, CouponIssuedEventPayload.class);
            return new CouponIssuedEvent(
                payload.eventId(),
                payload.userId(),
                new CouponCode(payload.couponCode()),
                payload.idempotencyKey(),
                payload.issuedAt()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox payload deserialization failed", e);
        }
    }
}
