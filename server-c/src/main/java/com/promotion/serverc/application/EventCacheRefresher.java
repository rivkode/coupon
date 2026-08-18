package com.promotion.serverc.application;

import com.promotion.serverc.api.dto.EventResponse;
import com.promotion.serverc.domain.EventStatus;
import com.promotion.serverc.infrastructure.persistence.EventJpaRepository;
import com.promotion.serverc.infrastructure.redis.EventCacheStore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Refresh-Ahead 패턴 — IN_PROGRESS 상태의 모든 이벤트를 주기적으로 DB 에서 읽어 Redis 에 다시 적재.
 *
 * <p>핵심: refresh-interval-ms (default 60s) < ttl-seconds (default 300s) 라서 캐시는 항상
 * "방금 갱신된 상태" 를 유지 → TTL 만료 자체가 일어나지 않음 → stampede 위험 제거.
 *
 * <p>대상 제한: status = IN_PROGRESS 만 갱신. 생성/종료/취소 상태는 빈번 조회가 없으므로 자연
 * 만료 허용.
 *
 * <p>{@code @Transactional} 을 붙이지 않는다. DB 조회는 repository 의 짧은 read 트랜잭션에서
 * 끝나고 결과는 이미 DTO 로 떠 있으므로, 이후 Redis 쓰기 루프를 트랜잭션 안에 두면 커넥션만
 * 그 시간만큼 잡고 있게 된다 (CLAUDE.md §10).
 */
@Component
@RequiredArgsConstructor
public class EventCacheRefresher {

    private static final Logger log = LoggerFactory.getLogger(EventCacheRefresher.class);

    private final EventJpaRepository eventRepository;
    private final EventCacheStore cacheStore;

    @Scheduled(fixedDelayString = "${app.event-cache.refresh-interval-ms:60000}")
    public void refreshActiveEvents() {
        List<EventResponse> active = eventRepository.findByStatus(EventStatus.IN_PROGRESS).stream()
                .map(EventResponse::from)
                .toList();

        for (EventResponse event : active) {
            cacheStore.put(event);
        }

        if (!active.isEmpty()) {
            log.debug("refreshed {} IN_PROGRESS event(s) in cache", active.size());
        }
    }
}
