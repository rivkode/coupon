package com.promotion.serverb.infrastructure.redis;

/** Server B Redis 키 컨벤션 (CLAUDE.md §9). */
public final class RedisKeys {

    private RedisKeys() {}

    /** 사용자 신청 hash — status / createdAt / requestId / eventId / code. */
    public static String pendingHash(long userId, long couponTypeId) {
        return "issue:pending:%d:%d".formatted(userId, couponTypeId);
    }

    /** member = "<userId>:<couponTypeId>", score = createdAt epoch ms. */
    public static final String PENDING_ZSET = "issue:pending:zset";

    public static String pendingZsetMember(long userId, long couponTypeId) {
        return "%d:%d".formatted(userId, couponTypeId);
    }
}
