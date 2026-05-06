package com.promotion.servera.infrastructure.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 응답 캐시 구현.
 *
 * <p>키: {@code idem:{userId}:{key}}, TTL: 24h (운영자 조정 가능).
 * 값: {@link CachedResponse} 의 JSON 직렬화.
 *
 * <p>현재는 단순 GET / SET — race 가 발생하면 두 요청 모두 downstream 진입. DB UNIQUE
 * constraint(`(user_id, idempotency_key)`) 가 최종 차단하므로 사용자 관점 안전 (CLAUDE.md
 * ADR-004 의 "1차 캐시 + 최종 DB UNIQUE" 정책).
 */
@Component
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisIdempotencyStore(
        StringRedisTemplate redis,
        ObjectMapper objectMapper,
        @Value("${app.idempotency.ttl-hours:24}") long ttlHours
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofHours(ttlHours);
    }

    @Override
    public Optional<CachedResponse> find(IdempotencyKey key) {
        String raw = redis.opsForValue().get(key.redisKey());
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, CachedResponse.class));
        } catch (JsonProcessingException ex) {
            log.warn("idempotency cache is corrupt; treating as miss: redisKey={} reason={}",
                key.redisKey(), ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(IdempotencyKey key, CachedResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redis.opsForValue().set(key.redisKey(), json, ttl);
        } catch (JsonProcessingException ex) {
            log.warn("idempotency cache write failed: redisKey={} reason={}",
                key.redisKey(), ex.getMessage());
        }
    }
}
