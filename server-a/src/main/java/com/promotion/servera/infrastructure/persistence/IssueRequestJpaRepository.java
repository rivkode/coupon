package com.promotion.servera.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface IssueRequestJpaRepository extends JpaRepository<IssueRequestJpaEntity, Long> {

    Optional<IssueRequestJpaEntity> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<IssueRequestJpaEntity> findByRequestId(String requestId);
}
