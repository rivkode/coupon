package com.promotion.servera.application;

import com.promotion.common.coupon.IssueAcceptanceStatus;
import com.promotion.servera.infrastructure.redis.CouponAvailabilityCache;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server A 의 통합 테스트 — 실제 MySQL 8.0 (Testcontainers) + WireMock(B) 위에서 검증.
 *
 * <p>핵심 검증:
 * <ul>
 *   <li>per-request commit 정합성 (200 동시 요청 → 정확히 200 row)</li>
 *   <li>B 5xx 반복 → Resilience4j Circuit Breaker OPEN 전이 + 후속 호출 차단 (ADR-001)</li>
 *   <li>B read-timeout (500ms) 초과 → INTERNAL_ERROR fallback + REJECTED row commit</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
class IssueRequestServiceIT {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("server_a")
            .withUsername("test")
            .withPassword("test");

    @RegisterExtension
    static final WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "32");
        registry.add("app.server-b.base-url", wireMock::baseUrl);
    }

    @Autowired
    IssueRequestService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    /** ADR-011: 본 IT 의 관심사는 Circuit Breaker / per-request commit — cache 는 항상 false 로 mock. */
    @MockBean
    CouponAvailabilityCache availabilityCache;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM issue_request");
        wireMock.resetAll();
        when(availabilityCache.isSoldOut(anyLong(), anyLong())).thenReturn(false);
        // CB sliding window / state 초기화 — 이전 테스트에서 OPEN 으로 끝난 상태가 다음 테스트에 누설되지 않도록.
        circuitBreakerRegistry.circuitBreaker("couponIssuing").reset();
    }

    /**
     * ① per-request commit 정합성: 200 동시 발급 요청 → 정확히 200 row, 모두 ACCEPTED.
     * (ADR-010 — batch insert 미사용. 1 요청 = 1 commit)
     */
    @Test
    void perRequestCommitsAllRowsUnderConcurrentTraffic() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/internal/v1/coupons/issue"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"requestId":"r-acpt","status":"ACCEPTED","message":null}
                                """)));

        int totalUsers = 200;
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(totalUsers);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < totalUsers; i++) {
            final long uid = i + 1;
            pool.submit(() -> {
                try {
                    start.await();
                    service.issue(new IssueCommand(uid, 1L, 1L));
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
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(finished, "executor did not finish in 60s");
        assertEquals(0, errors.get(), "no exceptions expected from service under happy path");

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM issue_request", Integer.class);
        Integer acceptedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM issue_request WHERE status = 'ACCEPTED'", Integer.class);

        assertNotNull(total);
        assertNotNull(acceptedRows);
        assertEquals(totalUsers, total.intValue(), "200 동시 요청 → 정확히 200 row commit");
        assertEquals(totalUsers, acceptedRows.intValue(), "모두 ACCEPTED status");
    }

    /**
     * ② B read-timeout (500ms) 초과 → fallback INTERNAL_ERROR + REJECTED row commit.
     */
    @Test
    void timeoutOnDownstreamFallsBackAndCommitsRejectedRow() {
        wireMock.stubFor(post(urlEqualTo("/internal/v1/coupons/issue"))
                .willReturn(aResponse()
                        .withFixedDelay(1500)
                        .withStatus(200)
                        .withBody("{}")));

        IssueOutcome outcome = service.issue(new IssueCommand(1L, 1L, 1L));

        assertEquals(IssueAcceptanceStatus.INTERNAL_ERROR, outcome.downstreamStatus());
        Integer rejected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM issue_request WHERE status = 'REJECTED'", Integer.class);
        assertNotNull(rejected);
        assertEquals(1, rejected.intValue(), "timeout 시에도 audit row 1 건 commit");
    }

    /**
     * ③ B 5xx 반복 → CB OPEN 전이 (sliding-window-size=20, min-calls=10, threshold=50%).
     * 10 회 5xx 호출 후 state=OPEN, 추가 호출은 즉시 차단되어 WireMock 까지 도달하지 않음.
     */
    @Test
    void circuitBreakerOpensAfterRepeatedDownstreamFailures() {
        wireMock.stubFor(post(urlEqualTo("/internal/v1/coupons/issue"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 10; i++) {
            service.issue(new IssueCommand(i + 1L, 1L, 1L));
        }

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("couponIssuing");
        assertEquals(CircuitBreaker.State.OPEN, cb.getState(),
                "10 회 5xx 후 sliding-window 50% threshold 초과 → OPEN");

        // OPEN 상태에서 추가 호출 → CallNotPermittedException → fallback INTERNAL_ERROR
        IssueOutcome blocked = service.issue(new IssueCommand(99L, 1L, 1L));
        assertEquals(IssueAcceptanceStatus.INTERNAL_ERROR, blocked.downstreamStatus());

        // WireMock 도달 호출 수가 OPEN 진입 직후 차단되었는지 확인 (sliding window 20 보다 작음).
        int wireMockCalls = wireMock.findAll(postRequestedFor(urlEqualTo("/internal/v1/coupons/issue"))).size();
        assertTrue(wireMockCalls <= 11,
                "CB OPEN 진입 후 호출이 차단되어 WireMock 도달 ≤ 11 (actual=" + wireMockCalls + ")");
    }
}
