package com.promotion.servera.infrastructure.redis;

/** Server A Redis 키 컨벤션. server-b/server-c 와 동일 키 포맷 공유. */
public final class RedisKeys {

    private RedisKeys() {}

    /**
     * coupon:available:{eventId}:{couponTypeId} — ADR-011 SOLD_OUT negative cache.
     * 키 존재 = 매진. A 가 진입에서 EXISTS 로 단락. server-c 가 SET, server-a 가 GET.
     */
    public static String couponAvailable(long eventId, long couponTypeId) {
        return "coupon:available:" + eventId + ":" + couponTypeId;
    }
}
