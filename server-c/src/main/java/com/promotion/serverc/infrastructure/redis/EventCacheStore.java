package com.promotion.serverc.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.serverc.api.dto.EventResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 이벤트 정보 Redis 캐시 — JSON 직렬화 + TTL.
 *
 * <p>Cache stampede 의 1 차 방어선은 EventCacheRefresher 의 Refresh-Ahead 로 TTL 만료 자체를
 * 회피하는 것. 본 클래스는 단순한 put/get 만 책임진다.
 */
@Component
public class EventCacheStore {

    private static final Logger log = LoggerFactory.getLogger(EventCacheStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public EventCacheStore(StringRedisTemplate redis,
                           ObjectMapper objectMapper,
                           @Value("${app.event-cache.ttl-seconds:300}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public void put(EventResponse event) {
        try {
            redis.opsForValue().set(
                    RedisKeys.event(event.eventId()),
                    objectMapper.writeValueAsString(event),
                    ttl
            );
        } catch (JsonProcessingException e) {
            // serialize 실패는 코드 결함 — 캐시 미적용은 fallback 으로 DB 가 받아주므로 swallow.
            log.warn("failed to serialize event for cache: eventId={} reason={}",
                    event.eventId(), e.getMessage());
        }
    }

    public Optional<EventResponse> get(long eventId) {
        String json;
        try {
            json = redis.opsForValue().get(RedisKeys.event(eventId));
        } catch (Exception ex) {
            // Redis 일시 장애 — DB fallback 가능하도록 빈 결과.
            log.warn("redis get failed: eventId={} reason={}", eventId, ex.getMessage());
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, EventResponse.class));
        } catch (JsonProcessingException e) {
            log.warn("malformed event cache entry — falling back to DB: eventId={} reason={}",
                    eventId, e.getMessage());
            return Optional.empty();
        }
    }
}
