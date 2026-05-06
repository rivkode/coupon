package com.promotion.serverb.infrastructure.redis;

import com.promotion.serverb.domain.Stock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Stock VO 의 분배 결과를 Redis 의 샤드 키에 SET.
 *
 * <p>호출 주체: 통합 테스트 setup (@BeforeEach) 또는 운영 분배 도구 (본 과제 미포함).
 * 운영 환경에서는 별도 admin endpoint 또는 cli 가 필요 — 본 과제는 발급 흐름의 정합성 검증에
 * 집중하여 미구현. README 트레이드오프 섹션에 명시.
 */
@Component
public class StockSeeder {

    private final StringRedisTemplate redis;

    public StockSeeder(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void seed(Stock stock) {
        for (int shardId = 0; shardId < stock.shardCount(); shardId++) {
            String key = RedisKeys.stockShard(stock.eventId(), shardId);
            String quantity = String.valueOf(stock.quantityFor(shardId));
            redis.opsForValue().set(key, quantity);
        }
    }
}
