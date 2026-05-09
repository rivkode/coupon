package com.promotion.serverc.infrastructure.persistence;

import com.promotion.serverc.domain.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventJpaRepository extends JpaRepository<EventJpaEntity, Long> {

    /**
     * EventCacheRefresher 가 백그라운드 갱신 대상으로 삼을 이벤트 목록.
     * status 컬럼에 인덱스가 있어 적은 비용으로 스캔 가능.
     */
    List<EventJpaEntity> findByStatus(EventStatus status);
}
