package com.promotion.serverc.application;

import com.promotion.serverc.api.dto.EventResponse;
import com.promotion.serverc.domain.exception.EventNotFoundException;
import com.promotion.serverc.infrastructure.persistence.EventJpaRepository;
import com.promotion.serverc.infrastructure.redis.EventCacheStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이벤트 조회 — Cache-Aside 패턴.
 *
 * <p>Stampede 방어:
 * <ul>
 *   <li>1 차 (주력): EventCacheRefresher 의 Refresh-Ahead 로 TTL 만료 자체 회피</li>
 *   <li>2 차 (fallback): 본 service 의 cache miss → DB 조회 → put. 신규 IN_PROGRESS 전환 직후
 *       1 tick 정도의 짧은 윈도우에서만 DB 가 받음 (트래픽 한계 < 1 vCPU 한계)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EventQueryService {

    private final EventCacheStore cacheStore;
    private final EventJpaRepository eventRepository;

    @Transactional(readOnly = true)
    public EventResponse findById(long eventId) {
        return cacheStore.get(eventId)
                .orElseGet(() -> {
                    EventResponse response = eventRepository.findById(eventId)
                            .map(EventResponse::from)
                            .orElseThrow(() -> new EventNotFoundException(eventId));
                    cacheStore.put(response);
                    return response;
                });
    }
}
