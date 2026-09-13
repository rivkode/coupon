package com.promotion.serverc.application;

import com.promotion.serverc.api.dto.EventResponse;
import com.promotion.serverc.domain.exception.EventNotFoundException;
import com.promotion.serverc.infrastructure.persistence.EventJpaRepository;
import com.promotion.serverc.infrastructure.redis.EventCacheStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 이벤트 조회 — Cache-Aside 패턴.
 *
 * <p>Stampede 방어:
 * <ul>
 *   <li>1 차 (주력): EventCacheRefresher 의 Refresh-Ahead 로 TTL 만료 자체 회피</li>
 *   <li>2 차 (fallback): 본 service 의 cache miss → DB 조회 → put. 신규 IN_PROGRESS 전환 직후
 *       1 tick 정도의 짧은 윈도우에서만 DB 가 받음 (트래픽 한계 < 1 vCPU 한계)</li>
 * </ul>
 *
 * <p>{@code @Transactional} 을 붙이지 않는다. 캐시 히트 경로는 DB 를 아예 쓰지 않고, 미스 경로도
 * 단건 조회 하나뿐이라 repository 의 트랜잭션으로 충분하다. 붙이면 Redis 조회와 쓰기가 DB
 * 트랜잭션 경계 안에 들어간다 (CLAUDE.md §10).
 */
@Service
@RequiredArgsConstructor
public class EventQueryService {

    private final EventCacheStore cacheStore;
    private final EventJpaRepository eventRepository;

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
