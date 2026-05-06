package com.promotion.servera.infrastructure.persistence;

import com.promotion.servera.domain.Event;

final class EventMapper {

    private EventMapper() {
    }

    static Event toDomain(EventJpaEntity entity) {
        return Event.reconstitute(
            entity.getId(),
            entity.getName(),
            entity.getTotalStock(),
            entity.getStartedAt(),
            entity.getEndedAt()
        );
    }
}
