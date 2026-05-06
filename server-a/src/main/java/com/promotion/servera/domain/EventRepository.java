package com.promotion.servera.domain;

import java.util.Optional;

public interface EventRepository {

    Optional<Event> findById(Long id);
}
