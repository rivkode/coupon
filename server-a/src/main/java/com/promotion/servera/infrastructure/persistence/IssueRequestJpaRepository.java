package com.promotion.servera.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface IssueRequestJpaRepository extends JpaRepository<IssueRequestJpaEntity, Long> {
}
