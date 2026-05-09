package com.promotion.servera.application;

import com.promotion.common.coupon.IssueAcceptanceResult;
import com.promotion.common.coupon.IssueAcceptanceStatus;
import com.promotion.servera.domain.IssueRequest;
import com.promotion.servera.domain.IssueRequestRepository;
import com.promotion.servera.domain.IssueRequestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Server A 진입 흐름의 핵심 비즈니스 로직 테스트.
 *
 * <p>요구사항: 인스턴스당 1000 TPS 동시 트래픽이 들어와도 — 모든 요청에 대해 (1) B 호출 + (2) per-request
 * commit 이 일관되게 수행되어야 한다. 단위 테스트에서는 mock 으로 호출 횟수와 일관성만 검증
 * (실제 TPS 측정은 k6 부하 시나리오 `issue-1k-tps.js`).
 */
@ExtendWith(MockitoExtension.class)
class IssueRequestServiceTest {

    @Mock
    private IssueRequestRepository repository;

    @Mock
    private CouponIssuingClient client;

    @InjectMocks
    private IssueRequestService service;

    @Test
    void mapsAcceptedToAcceptedStatus() {
        when(client.issue(anyLong(), anyLong(), anyLong()))
                .thenReturn(IssueAcceptanceResult.accepted("req-1"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        IssueOutcome outcome = service.issue(new IssueCommand(1L, 100L, 10L));

        assertEquals(IssueAcceptanceStatus.ACCEPTED, outcome.downstreamStatus());
        assertEquals(IssueRequestStatus.ACCEPTED, outcome.issueRequest().getStatus());
        verify(repository).save(any());
    }

    @Test
    void mapsDuplicateToDuplicateStatus() {
        when(client.issue(anyLong(), anyLong(), anyLong()))
                .thenReturn(IssueAcceptanceResult.duplicate("req-2"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        IssueOutcome outcome = service.issue(new IssueCommand(1L, 100L, 10L));

        assertEquals(IssueAcceptanceStatus.DUPLICATE, outcome.downstreamStatus());
        assertEquals(IssueRequestStatus.DUPLICATE, outcome.issueRequest().getStatus());
    }

    @Test
    void mapsInternalErrorToRejectedStatus() {
        when(client.issue(anyLong(), anyLong(), anyLong()))
                .thenReturn(IssueAcceptanceResult.internalError("circuit-open"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        IssueOutcome outcome = service.issue(new IssueCommand(1L, 100L, 10L));

        assertEquals(IssueAcceptanceStatus.INTERNAL_ERROR, outcome.downstreamStatus());
        assertTrue(outcome.isDownstreamUnavailable());
        assertEquals(IssueRequestStatus.REJECTED, outcome.issueRequest().getStatus());
    }

    /**
     * 동시 트래픽 스모크 — 200 동시 요청에 대해 client / repository 가 모두 호출되는지.
     * 실제 1000 TPS 측정은 k6, 단위 테스트는 일관성 invariant 만 검증.
     */
    @Test
    void allConcurrentRequestsReachClientAndRepository() throws Exception {
        int concurrency = 200;
        when(client.issue(anyLong(), anyLong(), anyLong()))
                .thenReturn(IssueAcceptanceResult.accepted("req-x"));
        when(repository.save(any())).thenAnswer(i -> {
            IssueRequest r = i.getArgument(0);
            return r;
        });

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();

        for (int i = 0; i < concurrency; i++) {
            final long uid = i + 1;
            pool.submit(() -> {
                try {
                    start.await();
                    service.issue(new IssueCommand(uid, 1L, 1L));
                    ok.incrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        boolean done = pool.awaitTermination(15, TimeUnit.SECONDS);
        assertTrue(done, "executor did not finish in time");

        assertEquals(concurrency, ok.get());
        verify(client, times(concurrency)).issue(anyLong(), anyLong(), anyLong());
        verify(repository, times(concurrency)).save(any());
    }
}
