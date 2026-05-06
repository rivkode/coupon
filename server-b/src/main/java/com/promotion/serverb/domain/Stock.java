package com.promotion.serverb.domain;

/**
 * 한 이벤트의 재고 분배 정의 (CLAUDE.md ADR-003 — Hot Spot 회피용 샤딩).
 *
 * <p>{@code totalQuantity} 를 {@link #DEFAULT_SHARD_COUNT} 개 샤드로 균등 분배.
 * 정수 나눗셈 후 남는 개수는 앞 샤드부터 1 씩 추가 (예: 10003 / 10 → 앞 3개 샤드는 1001, 나머지 7개는 1000).
 *
 * <p>실제 사용처: PR #9 의 stock seeder + Lua 스크립트의 입력. 본 PR (#8) 에서는 정의만 두고
 * 사용은 다음 PR 에서. 도메인에 두는 이유는 "재고는 RDBMS 가 아니라 Redis 가 권위" 라는 결정을
 * 코드의 형태로 명시하기 위함 — 아무 곳에서나 만질 수 없도록.
 *
 * <p>샤드 수의 진실(authoritative) 는 본 도메인이며, infrastructure (Redis 키 컨벤션) 가 본 값을
 * 참조한다. Domain → Infrastructure 단방향 의존 유지.
 */
public record Stock(long eventId, int totalQuantity, int shardCount) {

    /** 기본 샤드 수. infrastructure.redis.RedisKeys.STOCK_SHARDS 가 본 상수를 참조. */
    public static final int DEFAULT_SHARD_COUNT = 10;

    public Stock {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive: " + eventId);
        }
        if (totalQuantity <= 0) {
            throw new IllegalArgumentException("totalQuantity must be positive: " + totalQuantity);
        }
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive: " + shardCount);
        }
        if (totalQuantity < shardCount) {
            throw new IllegalArgumentException(
                "totalQuantity(%d) must be >= shardCount(%d) so each shard has at least 1"
                    .formatted(totalQuantity, shardCount));
        }
    }

    /**
     * 표준 샤드 수 ({@link #DEFAULT_SHARD_COUNT}) 로 새 Stock 생성.
     */
    public static Stock of(long eventId, int totalQuantity) {
        return new Stock(eventId, totalQuantity, DEFAULT_SHARD_COUNT);
    }

    /**
     * 주어진 샤드의 할당량. 균등 분배 + 나머지는 앞 샤드부터.
     * 예: totalQuantity=10003, shardCount=10
     *   - shardId 0..2 : 1001 (나머지 3 분배)
     *   - shardId 3..9 : 1000
     */
    public int quantityFor(int shardId) {
        if (shardId < 0 || shardId >= shardCount) {
            throw new IllegalArgumentException(
                "shardId out of range [0,%d): %d".formatted(shardCount, shardId));
        }
        int base = totalQuantity / shardCount;
        int remainder = totalQuantity % shardCount;
        return shardId < remainder ? base + 1 : base;
    }
}
