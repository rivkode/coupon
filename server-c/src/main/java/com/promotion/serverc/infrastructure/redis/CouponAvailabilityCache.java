package com.promotion.serverc.infrastructure.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * ADR-011 SOLD_OUT negative cache 의 쓰기 측 (server-c).
 *
 * <p>키 존재만으로 매진을 표현. 값은 의미 없음 ("1"). TTL 24h (B 의 pending hash TTL 와 동일).
 * 캐시 쓰기 실패는 catch + log — 다음 SOLD_OUT 처리 시 자연 회복 (재고는 여전히 0).
 *
 * <p>매번 SET 으로 TTL 이 갱신되는 것은 의도된 동작이다. 매진 상태에서 B 가 단락하므로 호출 빈도 자체가
 * 줄어들고, restock(재고 회복) 후 들어온 메시지는 fresh read 가 0 이 아니므로 SET 을 호출하지 않는다 →
 * stale 키는 24h 안에 자연 만료. NX 로 최초 1회만 적재할 경우 오히려 TTL 만료 후 재적재가 안 돼 부정확.
 */
@Component
public class CouponAvailabilityCache {

    private static final Logger log = LoggerFactory.getLogger(CouponAvailabilityCache.class);
    private static final String SENTINEL_VALUE = "1";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public CouponAvailabilityCache(StringRedisTemplate redis,
                                   @Value("${app.cache.coupon-available-ttl-seconds:86400}") long ttlSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public void markSoldOut(long eventId, long couponTypeId) {
        try {
            redis.opsForValue().set(RedisKeys.couponAvailable(eventId, couponTypeId), SENTINEL_VALUE, ttl);
            log.info("sold-out cache set: eventId={}, couponTypeId={}", eventId, couponTypeId);
        } catch (Exception ex) {
            // 권위는 MySQL — 캐시 실패는 다음 SOLD_OUT 호출이 자연 회복.
            log.warn("failed to set sold-out cache: eventId={}, couponTypeId={} reason={}",
                    eventId, couponTypeId, ex.getMessage());
        }
    }
}
