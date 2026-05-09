package com.promotion.serverb.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.common.coupon.IssueAcceptanceStatus;
import com.promotion.serverb.infrastructure.redis.RedisKeys;
import com.promotion.serverb.it.SharedContainers;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server B 의 발급 신청 접수 통합 테스트 — 실제 Redis(Testcontainers) + EmbeddedKafka.
 *
 * <p>핵심 검증:
 * <ul>
 *   <li>최초 신청 → ACCEPTED + Redis pending hash + zset 등록 + Kafka 토픽에 메시지 도착</li>
 *   <li>중복 신청 → DUPLICATE + Kafka 메시지 1 회만 발행</li>
 *   <li>같은 (user, type) 동시 10 호출 → 정확히 1 회만 ACCEPTED (Redis HSETNX 의 atomic)</li>
 * </ul>
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"coupon-issue-request", "coupon-issue-result"})
class CouponIssueAcceptServiceIT {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        SharedContainers.startAll();
        registry.add("spring.data.redis.host", SharedContainers::redisHost);
        registry.add("spring.data.redis.port", SharedContainers::redisPort);
    }

    @Autowired
    CouponIssueAcceptService service;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    ObjectMapper objectMapper;

    private Consumer<String, String> kafkaConsumer;

    @BeforeEach
    void resetState() {
        redisTemplate.getRequiredConnectionFactory().getConnection().serverCommands().flushDb();
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps(
                "test-accept-" + System.nanoTime(), "true", embeddedKafkaBroker));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        kafkaConsumer = new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), new StringDeserializer()).createConsumer();
        kafkaConsumer.subscribe(List.of("coupon-issue-request"));
        // 이전 IT 가 남긴 메시지를 drain — 본 테스트는 자기 publish 만 카운트해야.
        KafkaTestUtils.getRecords(kafkaConsumer, Duration.ofMillis(500));
    }

    @AfterEach
    void closeConsumer() {
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
    }

    @Test
    void firstAcceptanceStoresRedisAndPublishesKafka() throws Exception {
        var result = service.accept(1L, 100L, 10L);

        assertEquals(IssueAcceptanceStatus.ACCEPTED, result.status());
        assertTrue(redisTemplate.hasKey(RedisKeys.pendingHash(1L, 10L)),
                "pending hash 등록 — savePendingIfAbsent 의 first-write");
        Double score = redisTemplate.opsForZSet().score(
                RedisKeys.PENDING_ZSET, RedisKeys.pendingZsetMember(1L, 10L));
        assertNotNull(score, "zset 에도 등록 — 스케줄러가 cutoff 로 추출 가능");

        ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(
                kafkaConsumer, "coupon-issue-request", Duration.ofSeconds(10));
        assertNotNull(record);
        var node = objectMapper.readTree(record.value());
        assertEquals(1L, node.get("userId").asLong());
        assertEquals(100L, node.get("eventId").asLong());
        assertEquals(10L, node.get("couponTypeId").asLong());
    }

    @Test
    void duplicateRequestReturnsDuplicateAndDoesNotRePublish() {
        service.accept(1L, 100L, 10L);
        var second = service.accept(1L, 100L, 10L);

        assertEquals(IssueAcceptanceStatus.DUPLICATE, second.status());

        var records = KafkaTestUtils.getRecords(kafkaConsumer, Duration.ofSeconds(3));
        assertEquals(1, records.count(), "중복 신청은 Kafka 미발행 (Redis 단계에서 차단)");
    }

    /**
     * Redis HSETNX 의 atomicity 검증 — 같은 (user, type) 에 대한 동시 10 호출 시 정확히 1 건만 ACCEPTED.
     */
    @Test
    void concurrentSameUserRequestsResolveToExactlyOneAcceptance() throws Exception {
        int duplicates = 10;
        ExecutorService pool = Executors.newFixedThreadPool(duplicates);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(duplicates);
        AtomicInteger acceptedCount = new AtomicInteger();
        AtomicInteger duplicateCount = new AtomicInteger();

        for (int i = 0; i < duplicates; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var r = service.accept(7L, 100L, 10L);
                    if (r.status() == IssueAcceptanceStatus.ACCEPTED) {
                        acceptedCount.incrementAndGet();
                    } else if (r.status() == IssueAcceptanceStatus.DUPLICATE) {
                        duplicateCount.incrementAndGet();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished);

        assertEquals(1, acceptedCount.get(), "동시 10 호출 → 정확히 1 건만 ACCEPTED");
        assertEquals(duplicates - 1, duplicateCount.get(), "나머지는 모두 DUPLICATE");

        var records = KafkaTestUtils.getRecords(kafkaConsumer, Duration.ofSeconds(5));
        assertEquals(1, records.count(), "Kafka 발행도 정확히 1 회 (race-free)");
    }
}
