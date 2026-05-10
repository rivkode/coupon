package com.promotion.serverc.infrastructure.redis;

/** Server C Redis 키 컨벤션. */
public final class RedisKeys {

    private RedisKeys() {}

    /** event:{eventId} — JSON-serialized EventResponse, TTL 적용. */
    public static String event(long eventId) {
        return "event:" + eventId;
    }

    /**
     * coupon:available:{eventId}:{couponTypeId} — ADR-011 SOLD_OUT negative cache.
     * 키 존재 = 매진. 부재 = 사용 가능 또는 미정 (B 가 fall-through). 값 자체는 사용하지 않음.
     */
    public static String couponAvailable(long eventId, long couponTypeId) {
        return "coupon:available:" + eventId + ":" + couponTypeId;
    }
}
