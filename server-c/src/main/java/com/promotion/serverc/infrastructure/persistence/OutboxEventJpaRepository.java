package com.promotion.serverc.infrastructure.persistence;

import com.promotion.serverc.domain.OutboxEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, Long> {

    List<OutboxEventJpaEntity> findByStatusOrderByCreatedAtAsc(OutboxEventStatus status, Pageable pageable);
}
