package com.promotion.serverb.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.promotion.common.coupon.CouponCode;
import com.promotion.serverb.domain.Stock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Lua atomic 발급 + 보상 동작 검증.
 *
 * <p><b>사전 조건</b>: docker-compose 의 mysql / redis 가 호스트 포트로 떠 있어야 한다
 * (`docker compose up -d mysql redis`). Testcontainers 미사용 — Day 4 또는 후속 PR 에서 도입 결정.
 */
@SpringBootTest
@ActiveProfiles("local")
class RedisStockClientIT {

    private static final long EVENT_ID = 9999L;  // 통합 테스트 전용 (실 event 마스터와 분리)
    private static final long TTL = 3600L;

    @Autowired
    private RedisStockClient redis;

    @Autowired
    private com.promotion.serverb.infrastructure.redis.StockSeeder seeder;

    @Autowired
    private StringRedisTemplate template;

    @BeforeEach
    void cleanupKeys() {
        Set<String> stockKeys = template.keys("event:" + EVENT_ID + ":*");
        if (stockKeys != null && !stockKeys.isEmpty()) template.delete(stockKeys);
        Set<String> couponKeys = template.keys("coupon:*");
        if (couponKeys != null && !couponKeys.isEmpty()) template.delete(couponKeys);
    }

    @Test
    @DisplayName("ISSUED — 재고 차감 + idem 캐시 등록 + coupon hash TTL 설정")
    void issued_when_stock_available() {
        seeder.seed(Stock.of(EVENT_ID, 100));  // 10 샤드 × 10 = 100
        long userId = 1L;
        String idem = UUID.randomUUID().toString();
        CouponCode code = CouponCode.generate();

        LuaIssueResult result = redis.tryIssue(EVENT_ID, userId, idem, code, TTL, Instant.now());

        assertThat(result.status()).isEqualTo(LuaIssueStatus.ISSUED);
        assertThat(result.couponCode()).isEqualTo(code);

        int shard = RedisKeys.shardIdFor(userId);
        assertThat(template.opsForValue().get(RedisKeys.stockShard(EVENT_ID, shard))).isEqualTo("9");
        assertThat(template.opsForValue().get(RedisKeys.idempotencyKey(idem))).isEqualTo(code.value());
        // coupon hash 의 userId 필드 검증
        assertThat(template.<String, String>opsForHash()
            .get(RedisKeys.couponCode(code.value()), "userId")).isEqualTo(String.valueOf(userId));
    }

    @Test
    @DisplayName("ALREADY_ISSUED — 같은 idem 두 번째 호출은 첫 코드 반환, 재고 추가 차감 없음")
    void already_issued_returns_cached_code() {
        seeder.seed(Stock.of(EVENT_ID, 100));
        long userId = 2L;
        String idem = UUID.randomUUID().toString();
        CouponCode firstCode = CouponCode.generate();
        redis.tryIssue(EVENT_ID, userId, idem, firstCode, TTL, Instant.now());

        // 두 번째 시도 — 다른 후보 코드여도 idem 캐시 hit
        CouponCode secondCandidate = CouponCode.generate();
        LuaIssueResult result = redis.tryIssue(EVENT_ID, userId, idem, secondCandidate, TTL, Instant.now());

        assertThat(result.status()).isEqualTo(LuaIssueStatus.ALREADY_ISSUED);
        assertThat(result.couponCode()).isEqualTo(firstCode);

        int shard = RedisKeys.shardIdFor(userId);
        assertThat(template.opsForValue().get(RedisKeys.stockShard(EVENT_ID, shard))).isEqualTo("9");
    }

    @Test
    @DisplayName("SOLD_OUT — 재고 0 인 샤드에서 시도 시 DECR 후 INCR 로 음수 복구")
    void sold_out_when_shard_zero() {
        long userId = 3L;
        int shard = RedisKeys.shardIdFor(userId);
        String stockKey = RedisKeys.stockShard(EVENT_ID, shard);
        template.opsForValue().set(stockKey, "0");
        CouponCode candidate = CouponCode.generate();

        LuaIssueResult result = redis.tryIssue(
            EVENT_ID, userId, UUID.randomUUID().toString(), candidate, TTL, Instant.now());

        assertThat(result.status()).isEqualTo(LuaIssueStatus.SOLD_OUT);
        // 재고는 0 으로 복구 — DECR 로 -1 → INCR 으로 0
        assertThat(template.opsForValue().get(stockKey)).isEqualTo("0");
        // 코드 hash 도 정리
        assertThat(template.opsForHash().entries(RedisKeys.couponCode(candidate.value()))).isEmpty();
    }

    @Test
    @DisplayName("CODE_COLLISION — 같은 코드를 두 번 등록하면 두 번째는 거부")
    void code_collision_when_same_code_reused() {
        seeder.seed(Stock.of(EVENT_ID, 100));
        CouponCode sharedCode = CouponCode.generate();
        // 첫 번째 발급
        redis.tryIssue(EVENT_ID, 4L, UUID.randomUUID().toString(), sharedCode, TTL, Instant.now());

        // 두 번째 — 다른 사용자 / 다른 idem 이지만 같은 코드 (강제 충돌 시뮬레이션)
        LuaIssueResult result = redis.tryIssue(
            EVENT_ID, 5L, UUID.randomUUID().toString(), sharedCode, TTL, Instant.now());

        assertThat(result.status()).isEqualTo(LuaIssueStatus.CODE_COLLISION);
    }

    @Test
    @DisplayName("compensate OK — 재고 INCR + idem 캐시 + coupon hash 모두 정리")
    void compensate_restores_state() {
        seeder.seed(Stock.of(EVENT_ID, 100));
        long userId = 6L;
        String idem = UUID.randomUUID().toString();
        CouponCode code = CouponCode.generate();
        redis.tryIssue(EVENT_ID, userId, idem, code, TTL, Instant.now());
        int shard = RedisKeys.shardIdFor(userId);
        String stockKey = RedisKeys.stockShard(EVENT_ID, shard);
        assertThat(template.opsForValue().get(stockKey)).isEqualTo("9");

        CompensationResult comp = redis.compensate(EVENT_ID, userId, idem, code);

        assertThat(comp).isEqualTo(CompensationResult.OK);
        assertThat(template.opsForValue().get(stockKey)).isEqualTo("10");
        assertThat(template.opsForValue().get(RedisKeys.idempotencyKey(idem))).isNull();
        assertThat(template.opsForHash().entries(RedisKeys.couponCode(code.value()))).isEmpty();
    }

    @Test
    @DisplayName("compensate SKIPPED — idem 캐시가 만료(없음) 인 경우 INCR 이중 적용 방지")
    void compensate_skipped_when_idem_missing() {
        seeder.seed(Stock.of(EVENT_ID, 100));
        long userId = 8L;
        int shard = RedisKeys.shardIdFor(userId);
        String stockKey = RedisKeys.stockShard(EVENT_ID, shard);
        String idem = UUID.randomUUID().toString();
        // tryIssue 안 호출 — idem 캐시 / coupon hash 모두 비어있음 (TTL 만료 또는 외부 보상 선행 시뮬)
        CouponCode lostCode = CouponCode.generate();

        CompensationResult comp = redis.compensate(EVENT_ID, userId, idem, lostCode);

        assertThat(comp).isEqualTo(CompensationResult.SKIPPED);
        // 재고는 그대로 (이중 INCR 방지)
        assertThat(template.opsForValue().get(stockKey)).isEqualTo("10");
    }

    @Test
    @DisplayName("compensate SKIPPED — idem 캐시가 다른 코드를 가리키면 보상하지 않음")
    void compensate_skipped_when_idem_points_other_code() {
        seeder.seed(Stock.of(EVENT_ID, 100));
        long userId = 7L;
        String idem = UUID.randomUUID().toString();
        CouponCode realCode = CouponCode.generate();
        redis.tryIssue(EVENT_ID, userId, idem, realCode, TTL, Instant.now());
        int shard = RedisKeys.shardIdFor(userId);
        String stockKey = RedisKeys.stockShard(EVENT_ID, shard);

        // 잘못된 코드로 보상 호출 (실제로는 일어나지 않는 시나리오의 안전망 검증)
        CouponCode otherCode = CouponCode.generate();
        CompensationResult comp = redis.compensate(EVENT_ID, userId, idem, otherCode);

        assertThat(comp).isEqualTo(CompensationResult.SKIPPED);
        // 재고는 9 그대로 — 다른 요청의 정상 발급분이 보호됨
        assertThat(template.opsForValue().get(stockKey)).isEqualTo("9");
        assertThat(template.opsForValue().get(RedisKeys.idempotencyKey(idem))).isEqualTo(realCode.value());
    }
}
