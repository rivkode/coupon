package com.promotion.serverc.infrastructure.persistence;

import com.promotion.serverc.domain.OutboxEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, Long> {

    List<OutboxEventJpaEntity> findByStatusOrderByCreatedAtAsc(OutboxEventStatus status, Pageable pageable);

    /**
     * 발행에 성공한 건만 모아 한 번에 갱신한다. Kafka publish 를 트랜잭션 밖에서 끝낸 뒤 호출되므로
     * 이 트랜잭션에는 update 한 문장만 들어간다 (OutboxPoller 참고).
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update OutboxEventJpaEntity o set o.status = :status, o.publishedAt = :publishedAt "
            + "where o.outboxEventId in :ids")
    int markPublished(@Param("ids") List<Long> ids,
                      @Param("status") OutboxEventStatus status,
                      @Param("publishedAt") LocalDateTime publishedAt);
}
