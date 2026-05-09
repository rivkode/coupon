package com.promotion.serverb.application;

import com.promotion.serverb.infrastructure.redis.RedisIssueRequestStore;
import com.promotion.serverb.infrastructure.redis.RedisKeys;
import com.promotion.serverb.it.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * `@Scheduled` 보완 동작 통합 테스트 (CLAUDE.md ADR-008).
 *
 * <p>실제 Redis(Testcontainers) 의 ZSet score 기반 추출 + WireMock(C) 의 응답 시나리오별 분기 →
 * Redis hash 갱신 까지 검증.
 *
 * <p>cutoff = 0 초 / fixed-delay = 100ms 로 줄여 빠른 tick 에서 stale pending 처리 가능.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"coupon-issue-request", "coupon-issue-result"})
class PendingIssueSchedulerIT {

    @RegisterExtension
    static final WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        SharedContainers.startAll();
        registry.add("spring.data.redis.host", SharedContainers::redisHost);
        registry.add("spring.data.redis.port", SharedContainers::redisPort);
        registry.add("app.server-c.base-url", wireMock::baseUrl);
        // cutoff=0 + 빠른 tick → stale 판정/처리가 즉시 일어남.
        registry.add("app.scheduler.pending-cutoff-seconds", () -> "0");
        registry.add("app.scheduler.fixed-delay-ms", () -> "100");
    }

    @Autowired
    RedisIssueRequestStore store;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void resetState() {
        redisTemplate.getRequiredConnectionFactory().getConnection().serverCommands().flushDb();
        wireMock.resetAll();
    }

    @Test
    void schedulerSyncsSuccessFromC() {
        long userId = 1L;
        long couponTypeId = 10L;
        store.savePendingIfAbsent("req-1", userId, 100L, couponTypeId,
                Instant.now().minusSeconds(20));

        wireMock.stubFor(get(urlPathMatching("/internal/v1/users/1/coupons/10"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"success":true,"data":{"userId":1,"eventId":100,"couponTypeId":10,"code":"ABC123456789","status":"SUCCESS","issuedAt":"2026-05-09T20:00:00"}}
                                """)));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            String status = (String) redisTemplate.opsForHash()
                    .get(RedisKeys.pendingHash(userId, couponTypeId), "status");
            assertEquals("SUCCESS", status, "C 에서 SUCCESS 반환 → Redis status 동기화");
        });
    }

    @Test
    void schedulerMarksFailedWhenCReturnsNotFound() {
        long userId = 2L;
        long couponTypeId = 10L;
        store.savePendingIfAbsent("req-2", userId, 100L, couponTypeId,
                Instant.now().minusSeconds(20));

        wireMock.stubFor(get(urlPathMatching("/internal/v1/users/2/coupons/10"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":{\"code\":\"NOT_FOUND\"}}")));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            String status = (String) redisTemplate.opsForHash()
                    .get(RedisKeys.pendingHash(userId, couponTypeId), "status");
            assertEquals("FAILED", status,
                    "C 에 user_coupon 없음 → Kafka 메시지 유실로 간주, FAILED 로 마감");
        });
    }

    @Test
    void schedulerRetainsPendingWhenLookupFails() throws Exception {
        long userId = 3L;
        long couponTypeId = 10L;
        store.savePendingIfAbsent("req-3", userId, 100L, couponTypeId,
                Instant.now().minusSeconds(20));

        wireMock.stubFor(get(urlPathMatching("/internal/v1/users/3/coupons/10"))
                .willReturn(aResponse().withStatus(500)));

        // 1 초 대기 후에도 status 가 PENDING 으로 유지되어야 (다음 cycle 재시도 위해 zset 도 유지).
        Thread.sleep(1000);

        String status = (String) redisTemplate.opsForHash()
                .get(RedisKeys.pendingHash(userId, couponTypeId), "status");
        Double score = redisTemplate.opsForZSet()
                .score(RedisKeys.PENDING_ZSET, RedisKeys.pendingZsetMember(userId, couponTypeId));

        assertEquals("PENDING", status, "5xx 시 status 유지 — 다음 cycle 재시도");
        assertNotNull(score, "5xx 시 zset 유지");
    }
}
