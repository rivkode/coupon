package com.promotion.servera.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface EventJpaRepository extends JpaRepository<EventJpaEntity, Long> {
}
