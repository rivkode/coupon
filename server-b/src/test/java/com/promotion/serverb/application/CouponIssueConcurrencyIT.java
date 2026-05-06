package com.promotion.serverb.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.promotion.common.coupon.IssueResult;
import com.promotion.common.coupon.IssueStatus;
import com.promotion.serverb.domain.CouponIssueOutboxRepository;
import com.promotion.serverb.domain.Stock;
import com.promotion.serverb.infrastructure.redis.RedisKeys;
import com.promotion.serverb.infrastructure.redis.StockSeeder;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Lua atomic 발급 + Outbox + 보상 흐름의 동시성 정합성 검증.
 *
 * <p><b>시나리오</b>: 1,200 사용자가 동시 발급 시도, 재고 1,000장. (정합성 검증에 충분)
 * 결과는:
 * <ul>
 *   <li>ISSUED ≤ 1,000 (재고 권위 — Lua 가 음수 차단)</li>
 *   <li>ISSUED + SOLD_OUT == 1,200 (모든 사용자 처리됨, 중복/누락 없음)</li>
 *   <li>Outbox 행 수 == ISSUED (보상 호출 없음 = 정상 흐름에서 모두 INSERT 성공)</li>
 *   <li>Redis 재고 합계 == 1,000 - ISSUED</li>
 * </ul>
 *
 * <p>본 테스트는 <b>race condition / 동시성 정합성</b> 만 검증. 실제 1 vCPU / 10,000 TPS 부하
 * 시뮬레이션은 Day 4 의 k6 부하 테스트에서 별도 진행 (Tomcat / Hikari / Redis 풀 한계 검증).
 *
 * <p>샤드 hash 편차 + concurrency 로 ISSUED 가 1,000 미만일 수 있음 (자기 샤드 SOLD_OUT 시 다른
 * 샤드 fallback 미구현 — 알려진 trade-off, README 명시). 안전 마진 ISSUED ≥ 950.
 *
 * <p><b>사전 조건</b>: docker-compose 의 mysql / redis (Day 4 또는 후속 PR 에서 Testcontainers 도입 결정).
 */
@SpringBootTest
@ActiveProfiles("local")
class CouponIssueConcurrencyIT {

    private static final long EVENT_ID = 99_999L;
    private static final int TOTAL_STOCK = 1_000;
    private static final int CONCURRENT_USERS = 1_200;
    private static final int THREAD_POOL = 32;

    @Autowired private CouponIssueService service;
    @Autowired private StockSeeder seeder;
    @Autowired private StringRedisTemplate redis;
    @Autowired private CouponIssueOutboxRepository outboxRepository;

    @BeforeEach
    void seedAndCleanup() {
        Set<String> stockKeys = redis.keys("event:" + EVENT_ID + ":*");
        if (stockKeys != null && !stockKeys.isEmpty()) redis.delete(stockKeys);
        Set<String> couponKeys = redis.keys("coupon:*");
        if (couponKeys != null && !couponKeys.isEmpty()) redis.delete(couponKeys);
        outboxRepository.deleteAll();

        seeder.seed(Stock.of(EVENT_ID, TOTAL_STOCK));
    }

    @Test
    @DisplayName("1,200 동시 발급 / 재고 1,000 → ISSUED 정확성 + Outbox 일치 + Redis 합계 일관성")
    void concurrent_issue_keeps_invariants() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL);
        CountDownLatch done = new CountDownLatch(CONCURRENT_USERS);
        AtomicInteger issued = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        long t0 = System.nanoTime();
        // THREAD_POOL 개의 worker 가 1,200 task 를 queue 처리. 동시 contention 은 같은 샤드에 몰리는
        // 사용자들 사이에서 자연 발생 — 명시적 start latch 가 없어도 Lua atomic 검증에 충분.
        for (int i = 0; i < CONCURRENT_USERS; i++) {
            long userId = i + 1L;
            String idem = "concurrent-" + userId;
            executor.submit(() -> {
                try {
                    IssueResult result = service.issue(new IssueCommand(EVENT_ID, userId, idem));
                    if (result.status() == IssueStatus.ISSUED) issued.incrementAndGet();
                    else if (result.status() == IssueStatus.SOLD_OUT) soldOut.incrementAndGet();
                    else errors.incrementAndGet();
                } catch (Exception ex) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        boolean finished = done.await(120, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        executor.shutdownNow();

        assertThat(finished).as("모든 요청이 120초 안에 완료").isTrue();

        // 1. 재고 권위 — ISSUED 는 절대 재고를 초과하지 않음
        assertThat(issued.get()).isLessThanOrEqualTo(TOTAL_STOCK);

        // 2. 누락/중복 없음 — 모든 요청은 ISSUED 또는 SOLD_OUT 으로 처리
        assertThat(issued.get() + soldOut.get()).isEqualTo(CONCURRENT_USERS);
        assertThat(errors.get()).isZero();

        // 3. Outbox 적재 일관성 — ISSUED 는 모두 Outbox 에 1건씩
        assertThat(outboxRepository.count()).isEqualTo(issued.get());

        // 4. Redis 재고 합계 = 미발급 분
        long redisRemaining = 0;
        for (int s = 0; s < Stock.DEFAULT_SHARD_COUNT; s++) {
            String v = redis.opsForValue().get(RedisKeys.stockShard(EVENT_ID, s));
            redisRemaining += Long.parseLong(v);
        }
        assertThat(redisRemaining).isEqualTo(TOTAL_STOCK - issued.get());

        // 5. 샤드 편차 안전 마진
        assertThat(issued.get()).as("샤드 hash 편차에 대한 안전 마진").isGreaterThanOrEqualTo(950);

        System.out.printf(
            "%n[concurrency] users=%d stock=%d -> ISSUED=%d SOLD_OUT=%d errors=%d elapsed=%dms%n",
            CONCURRENT_USERS, TOTAL_STOCK, issued.get(), soldOut.get(), errors.get(), elapsedMs);
    }
}
