package com.promotion.servera.infrastructure.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * ADR-011 SOLD_OUT negative cache 의 읽기 측 (server-a 진입 단락용).
 *
 * <p>키 존재만으로 매진 판정. Redis blip 시 false 로 fallback → B 호출 진행 (안전한 degradation).
 * 매진된 이벤트의 후속 요청은 A 진입에서 차단되어 B/C 까지 가지 않음 — 가장 일찍 차단.
 */
@Component
public class CouponAvailabilityCache {

    private static final Logger log = LoggerFactory.getLogger(CouponAvailabilityCache.class);

    private final StringRedisTemplate redis;

    public CouponAvailabilityCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isSoldOut(long eventId, long couponTypeId) {
        try {
            Boolean exists = redis.hasKey(RedisKeys.couponAvailable(eventId, couponTypeId));
            return Boolean.TRUE.equals(exists);
        } catch (Exception ex) {
            // Redis 일시 장애 — 정상 흐름으로 fall-through. C MySQL 권위가 정합성 보호.
            log.warn("sold-out cache read failed (fall-through to B): eventId={}, couponTypeId={} reason={}",
                    eventId, couponTypeId, ex.getMessage());
            return false;
        }
    }
}
