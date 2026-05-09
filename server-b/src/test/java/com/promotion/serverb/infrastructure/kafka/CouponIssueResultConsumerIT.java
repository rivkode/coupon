package com.promotion.serverb.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.common.coupon.CouponIssueResultPayload;
import com.promotion.common.coupon.CouponIssueResultStatus;
import com.promotion.serverb.infrastructure.redis.RedisIssueRequestStore;
import com.promotion.serverb.infrastructure.redis.RedisKeys;
import com.promotion.serverb.it.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * C → B Kafka result consumer 의 통합 동작 — 실제 Kafka publish → listener consume → Redis 갱신
 * 까지 round-trip.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"coupon-issue-request", "coupon-issue-result"})
class CouponIssueResultConsumerIT {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        SharedContainers.startAll();
        registry.add("spring.data.redis.host", SharedContainers::redisHost);
        registry.add("spring.data.redis.port", SharedContainers::redisPort);
    }

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RedisIssueRequestStore store;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void resetRedis() {
        redisTemplate.getRequiredConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void successResultUpdatesRedisHashAndRemovesFromZset() throws Exception {
        long userId = 1L;
        long couponTypeId = 10L;
        store.savePendingIfAbsent("req-1", userId, 100L, couponTypeId, Instant.now());

        var payload = new CouponIssueResultPayload(
                "req-1", userId, 100L, couponTypeId,
                CouponIssueResultStatus.SUCCESS, "ABC123456789", Instant.now());
        kafkaTemplate.send("coupon-issue-result",
                String.valueOf(userId),
                objectMapper.writeValueAsString(payload)).get(5, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            String status = (String) redisTemplate.opsForHash()
                    .get(RedisKeys.pendingHash(userId, couponTypeId), "status");
            String code = (String) redisTemplate.opsForHash()
                    .get(RedisKeys.pendingHash(userId, couponTypeId), "code");
            Double score = redisTemplate.opsForZSet()
                    .score(RedisKeys.PENDING_ZSET, RedisKeys.pendingZsetMember(userId, couponTypeId));

            assertEquals("SUCCESS", status);
            assertEquals("ABC123456789", code);
            assertNull(score, "종료 상태 — zset 에서 제거되어 스케줄러가 다시 잡지 않음");
        });
    }

    @Test
    void soldOutResultUpdatesStatusOnly() throws Exception {
        long userId = 2L;
        long couponTypeId = 10L;
        store.savePendingIfAbsent("req-2", userId, 100L, couponTypeId, Instant.now());

        var payload = new CouponIssueResultPayload(
                "req-2", userId, 100L, couponTypeId,
                CouponIssueResultStatus.SOLD_OUT, null, Instant.now());
        kafkaTemplate.send("coupon-issue-result",
                String.valueOf(userId),
                objectMapper.writeValueAsString(payload)).get(5, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            String status = (String) redisTemplate.opsForHash()
                    .get(RedisKeys.pendingHash(userId, couponTypeId), "status");
            assertEquals("SOLD_OUT", status);
        });
    }
}
