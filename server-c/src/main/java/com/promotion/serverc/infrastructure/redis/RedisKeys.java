package com.promotion.serverc.infrastructure.redis;

/** Server C Redis 키 컨벤션. */
public final class RedisKeys {

    private RedisKeys() {}

    /** event:{eventId} — JSON-serialized EventResponse, TTL 적용. */
    public static String event(long eventId) {
        return "event:" + eventId;
    }
}
