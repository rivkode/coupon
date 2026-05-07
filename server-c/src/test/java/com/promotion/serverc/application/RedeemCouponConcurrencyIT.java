package com.promotion.serverc.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.promotion.common.coupon.CouponCode;
import com.promotion.serverc.domain.Coupon;
import com.promotion.serverc.domain.CouponRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Redeem 동시성 검증 (CLAUDE.md ADR-007 — 낙관적 락).
 *
 * <p>같은 user 가 같은 coupon 을 5 thread 에서 동시에 redeem 시도:
 * <ul>
 *   <li>1건은 정상 (newlyRedeemed=true)</li>
 *   <li>나머지 4건은 (a) 첫 번째가 commit 전이면 OptimisticLockingFailureException → 클라이언트 retry,
 *       (b) 첫 번째 commit 후이면 멱등 분기 → newlyRedeemed=false</li>
 * </ul>
 *
 * <p>핵심 검증: <b>DB 의 used_at 이 정확히 한 번만 set 되었는가</b>.
 *
 * <p>사전 조건: docker-compose 의 mysql.
 */
@SpringBootTest
@Sql(statements = "DELETE FROM coupon", executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class RedeemCouponConcurrencyIT {

    private static final int CONCURRENCY = 5;

    @Autowired private CouponRepository couponRepository;
    @Autowired private RedeemCouponService redeemCouponService;

    @Test
    @DisplayName("같은 user 5 thread 동시 redeem — DB 에 정확히 1건의 used_at 만, 1 SUCCESS + 4 흡수(멱등 또는 retry)")
    void concurrent_redeem_results_in_single_used_at() throws Exception {
        long userId = 7777L;
        CouponCode code = CouponCode.generate();
        couponRepository.save(Coupon.issue(
            code, userId, 1L, "idem-issue-" + UUID.randomUUID(),
            Instant.parse("2026-05-07T08:00:00Z")));

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENCY);
        AtomicInteger newlyRedeemed = new AtomicInteger();
        AtomicInteger idempotent = new AtomicInteger();
        AtomicInteger optimisticLockFailures = new AtomicInteger();

        for (int i = 0; i < CONCURRENCY; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    try {
                        RedeemResult result = redeemCouponService.redeem(
                            new RedeemCommand(code, userId, "idem-redeem-" + UUID.randomUUID()));
                        if (result.newlyRedeemed()) newlyRedeemed.incrementAndGet();
                        else idempotent.incrementAndGet();
                    } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                        optimisticLockFailures.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // 핵심 — 정확히 한 번만 newlyRedeemed=true.
        assertThat(newlyRedeemed.get()).isEqualTo(1);
        assertThat(newlyRedeemed.get() + idempotent.get() + optimisticLockFailures.get())
            .isEqualTo(CONCURRENCY);
        // 5 thread 가 거의 동시에 진입하면 모두 commit 전 read 가 일반적이라 race 가 반드시 1회 이상 발생.
        // (idempotent 분기는 commit 후 들어온 요청에서만 — 본 IT 가 아닌 sequential IT 에서 명시 검증.)
        assertThat(optimisticLockFailures.get()).isGreaterThanOrEqualTo(1);

        // DB 상태 — used_at 이 set 되어 있고, 단일 행만 존재.
        Coupon persisted = couponRepository.findByCode(code).orElseThrow();
        assertThat(persisted.isUsed()).isTrue();
        assertThat(persisted.getUsedAt()).isNotNull();
        assertThat(couponRepository.findByUserId(userId)).hasSize(1);
    }

    @Test
    @DisplayName("순차 재호출 — 같은 user 의 두 번째 redeem 은 멱등 분기 (newlyRedeemed=false, 기존 redeemedAt)")
    void sequential_replay_returns_idempotent() {
        long userId = 8888L;
        CouponCode code = CouponCode.generate();
        couponRepository.save(Coupon.issue(
            code, userId, 1L, "idem-issue-" + UUID.randomUUID(),
            Instant.parse("2026-05-07T08:00:00Z")));

        RedeemResult first = redeemCouponService.redeem(
            new RedeemCommand(code, userId, "idem-redeem-1"));
        assertThat(first.newlyRedeemed()).isTrue();
        // DB DATETIME(3) 정밀도 — first.redeemedAt() 은 in-memory microsecond, DB 읽기 시 millis 로 truncate.
        // 멱등 분기의 정확한 비교는 DB 에 영속된 값과의 일치.
        Instant persistedRedeemedAt = couponRepository.findByCode(code).orElseThrow().getUsedAt();

        // 두 번째 호출 — 다른 idempotency-key 라도 본질적 멱등 (도메인 자체 멱등성).
        RedeemResult second = redeemCouponService.redeem(
            new RedeemCommand(code, userId, "idem-redeem-2"));
        assertThat(second.newlyRedeemed()).isFalse();
        assertThat(second.redeemedAt()).isEqualTo(persistedRedeemedAt);
        assertThat(second.code()).isEqualTo(code);
        assertThat(second.userId()).isEqualTo(userId);
    }
}
