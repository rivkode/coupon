package com.promotion.serverc.application;

import com.promotion.common.coupon.CouponIssueRequestPayload;
import com.promotion.serverc.domain.UserCouponStatus;
import com.promotion.serverc.infrastructure.persistence.CouponTypeInventoryJpaEntity;
import com.promotion.serverc.infrastructure.persistence.CouponTypeInventoryJpaRepository;
import com.promotion.serverc.infrastructure.persistence.CouponTypeJpaEntity;
import com.promotion.serverc.infrastructure.persistence.CouponTypeJpaRepository;
import com.promotion.serverc.infrastructure.persistence.EventJpaEntity;
import com.promotion.serverc.infrastructure.persistence.EventJpaRepository;
import com.promotion.serverc.infrastructure.persistence.OutboxEventJpaRepository;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaEntity;
import com.promotion.serverc.infrastructure.persistence.UserCouponJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server C 의 비관적 락 정합성 검증 (CLAUDE.md ADR-003).
 *
 * <p>실제 MySQL 8.0 InnoDB 위에서 {@code SELECT ... FOR UPDATE} 의 락 경합을 측정 — 단위 테스트
 * (mock 기반) 으론 검증 불가능한 영역. 200 명의 서로 다른 사용자가 동시에 100 개 재고에 대해
 * 발급을 요청했을 때, 정확히 100 명만 SUCCESS / 나머지 100 명은 SOLD_OUT 으로 분류되어야 한다.
 *
 * <p>전제: docker daemon 활성. Testcontainers 가 MySQL 8.0 컨테이너를 ad-hoc 부팅 + Flyway
 * V1 마이그레이션 적용. EmbeddedKafka 는 spring-kafka 의 KafkaListenerContainerFactory /
 * KafkaTemplate / KafkaAdmin 빈을 부팅 통과시키기 위함 (실제 publish/consume 검증은 별개).
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"coupon-issue-request", "coupon-issue-result"})
class CouponIssueProcessorConcurrencyIT {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("server_c")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // 동시성 테스트 — Hikari pool 을 thread 수 이상으로 확장.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "32");
    }

    @Autowired
    CouponIssueProcessor processor;

    @Autowired
    EventJpaRepository eventRepo;

    @Autowired
    CouponTypeJpaRepository couponTypeRepo;

    @Autowired
    CouponTypeInventoryJpaRepository inventoryRepo;

    @Autowired
    UserCouponJpaRepository userCouponRepo;

    @Autowired
    OutboxEventJpaRepository outboxRepo;

    private long eventId;
    private long couponTypeId;

    @BeforeEach
    void seedFreshFixtures() {
        outboxRepo.deleteAllInBatch();
        userCouponRepo.deleteAllInBatch();
        inventoryRepo.deleteAllInBatch();
        couponTypeRepo.deleteAllInBatch();
        eventRepo.deleteAllInBatch();

        EventJpaEntity event = eventRepo.save(new EventJpaEntity(
                "concurrency-test-event", "test",
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1)));
        eventId = event.getEventId();
        CouponTypeJpaEntity ct = couponTypeRepo.save(new CouponTypeJpaEntity(eventId, "10pct", 10));
        couponTypeId = ct.getCouponTypeId();
        inventoryRepo.save(new CouponTypeInventoryJpaEntity(eventId, couponTypeId, 100));
    }

    /**
     * 비관적 락 정합성 — 200 동시 요청 vs 재고 100 → 정확히 100 SUCCESS / 100 SOLD_OUT,
     * inventory.available_count = 0.
     *
     * <p>실패 모드:
     * <ul>
     *   <li>락 누락 시 → SUCCESS > 100 (over-issue) / available_count < 0</li>
     *   <li>모든 트랜잭션이 같은 inventory row 를 보지 못하면 → 전부 SUCCESS 가능 (race)</li>
     * </ul>
     */
    @Test
    void exactlyInventoryCountSucceedsUnderConcurrency() throws Exception {
        int totalUsers = 200;
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(totalUsers);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < totalUsers; i++) {
            final long userId = i + 1;
            pool.submit(() -> {
                try {
                    start.await();
                    processor.process(new CouponIssueRequestPayload(
                            UUID.randomUUID().toString(),
                            userId,
                            eventId,
                            couponTypeId,
                            Instant.now()
                    ));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished, "executor did not finish in 120s — likely lock-wait timeout");
        assertEquals(0, errors.get(), "no exceptions expected from processor under contention");

        List<UserCouponJpaEntity> all = userCouponRepo.findAll();
        long success = all.stream().filter(uc -> uc.getStatus() == UserCouponStatus.SUCCESS).count();
        long soldOut = all.stream().filter(uc -> uc.getStatus() == UserCouponStatus.SOLD_OUT).count();

        CouponTypeInventoryJpaEntity inv = inventoryRepo
                .findByEventIdAndCouponTypeId(eventId, couponTypeId)
                .orElseThrow();

        assertEquals(100, success, "정확히 inventory 만큼만 SUCCESS — 비관적 락 정합성");
        assertEquals(100, soldOut, "초과 신청 100 건은 SOLD_OUT");
        assertEquals(0, inv.getAvailableCount(), "재고 row 의 available_count = 0");
        assertEquals(totalUsers, all.size(), "모든 사용자에 대해 user_coupon row 1 건씩 생성");
    }

    /**
     * 같은 user 가 같은 coupon_type 에 대해 5 번 동시 요청 → UNIQUE constraint 가 정확히 1 건만 발급.
     * (CLAUDE.md ADR-004 — `(user_id, coupon_type_id)` UNIQUE 가 1 인 1 장 + 멱등성 동시 보장)
     */
    @Test
    void sameUserDuplicateRequestsResultInOnlyOneIssue() throws Exception {
        long fixedUserId = 9999L;
        int duplicates = 5;
        ExecutorService pool = Executors.newFixedThreadPool(duplicates);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(duplicates);

        for (int i = 0; i < duplicates; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    processor.process(new CouponIssueRequestPayload(
                            UUID.randomUUID().toString(),
                            fixedUserId, eventId, couponTypeId, Instant.now()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // race 시 DataIntegrityViolation 가능 — processor 가 흡수.
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished);

        List<UserCouponJpaEntity> userCoupons = userCouponRepo.findAll().stream()
                .filter(uc -> uc.getUserId() == fixedUserId)
                .toList();

        assertEquals(1, userCoupons.size(), "같은 user 의 5 동시 요청 → 1 건만 발급 (UNIQUE)");
    }
}
