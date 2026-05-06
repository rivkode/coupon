package com.promotion.servera.infrastructure.persistence;

import com.promotion.servera.domain.Event;
import com.promotion.servera.domain.EventRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class EventRepositoryImpl implements EventRepository {

    private final EventJpaRepository jpaRepository;

    @Override
    public Optional<Event> findById(Long id) {
        return jpaRepository.findById(id).map(EventMapper::toDomain);
    }
}
