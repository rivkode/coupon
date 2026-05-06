package com.promotion.serverb.infrastructure.redis;

import com.promotion.serverb.domain.Stock;

/**
 * Server B 가 사용하는 Redis 키 컨벤션 (CLAUDE.md §9 기반).
 *
 * <p>샤딩: 단일 키에 트래픽이 몰리는 Hot Spot 방지를 위해 재고를 {@link #STOCK_SHARDS}개로 분할.
 * 사용자가 hash(userId) % STOCK_SHARDS 로 샤드 라우팅 (CLAUDE.md ADR-003).
 *
 * <p>샤드 수의 권위는 도메인 ({@link Stock#DEFAULT_SHARD_COUNT}). 본 클래스는 그 결정을 Redis 키
 * 컨벤션에서 참조할 뿐 — Domain → Infrastructure 단방향 의존을 깨지 않기 위함.
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 재고 샤드 수. 진실은 {@link Stock#DEFAULT_SHARD_COUNT}. */
    public static final int STOCK_SHARDS = Stock.DEFAULT_SHARD_COUNT;

    /** event:{eventId}:stock:{shardId} — INTEGER, atomic DECR / Lua. */
    public static String stockShard(long eventId, int shardId) {
        if (shardId < 0 || shardId >= STOCK_SHARDS) {
            throw new IllegalArgumentException("shardId out of range [0,%d): %d".formatted(STOCK_SHARDS, shardId));
        }
        return "event:%d:stock:%d".formatted(eventId, shardId);
    }

    /** coupon:code:{code} — HASH, 임시 발급 정보 (Kafka publish 후 TTL 만료). */
    public static String couponCode(String code) {
        return "coupon:code:" + code;
    }

    /**
     * coupon:idem:{userId}:{idempotencyKey} — STRING, 발급된 couponCode 의 idempotency 1차 캐시.
     *
     * <p>user-scoped 키 — server-a 의 {@code (user_id, idempotency_key) UNIQUE} (ADR-004) 와
     * 정합. 같은 idem 으로 다른 user 가 호출해도 서로 다른 발급 결과를 받는다 (cross-user 충돌 회피).
     *
     * <p>server-a 의 IdempotencyFilter 가 분산 캐시여도 race 가능하므로 server-b 에도 1차 캐시
     * 보유. 최종 멱등성 보장은 Server C 의 coupon.idempotency_key UNIQUE.
     */
    public static String idempotencyKey(long userId, String idempotencyKey) {
        return "coupon:idem:%d:%s".formatted(userId, idempotencyKey);
    }

    /** 사용자 hash 기반 샤드 라우팅. */
    public static int shardIdFor(long userId) {
        return Math.floorMod(Long.hashCode(userId), STOCK_SHARDS);
    }
}
