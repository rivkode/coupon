package com.promotion.serverc.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.promotion.serverc.domain.EventStatus;
import com.promotion.serverc.infrastructure.persistence.EventJpaEntity;

import java.time.LocalDateTime;

/** GET /api/v1/events/{eventId} 응답 + Redis 캐시 직렬화 형. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventResponse(
        long eventId,
        String name,
        String content,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        EventStatus status
) {
    public static EventResponse from(EventJpaEntity event) {
        return new EventResponse(
                event.getEventId(),
                event.getName(),
                event.getContent(),
                event.getStartedAt(),
                event.getEndedAt(),
                event.getStatus()
        );
    }
}
