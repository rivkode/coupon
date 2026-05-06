package com.promotion.servera.api.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.servera.infrastructure.ratelimit.UserRateLimiter;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private UserRateLimiter rateLimiter;
    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(UserRateLimiter.class);
        chain = mock(FilterChain.class);
        filter = new RateLimitFilter(rateLimiter, new ObjectMapper());
    }

    private MockHttpServletRequest requestWithUser(String userId) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/coupons/issue-requests");
        if (userId != null) req.addHeader("X-User-Id", userId);
        return req;
    }

    @Test
    @DisplayName("X-User-Id 누락 → rateLimiter 호출 없이 통과")
    void missing_user_id_passes_through() throws Exception {
        MockHttpServletRequest req = requestWithUser(null);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verify(rateLimiter, never()).tryConsume(anyLong());
    }

    @Test
    @DisplayName("X-User-Id 가 숫자 아님 → 통과 (Controller 의 400 처리에 위임)")
    void non_numeric_user_id_passes_through() throws Exception {
        MockHttpServletRequest req = requestWithUser("abc");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    @DisplayName("토큰 소비 성공 → 통과 + X-RateLimit-Remaining 헤더")
    void consumed_passes_with_remaining_header() throws Exception {
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(true);
        when(probe.getRemainingTokens()).thenReturn(7L);
        when(rateLimiter.tryConsume(eq(1001L))).thenReturn(probe);

        MockHttpServletRequest req = requestWithUser("1001");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(res.getHeader("X-RateLimit-Remaining")).isEqualTo("7");
    }

    @Test
    @DisplayName("토큰 소비 실패 → 429 + Retry-After + RATE_LIMIT_EXCEEDED body, chain 호출 안 함")
    void rejected_writes_429_with_retry_after_and_body() throws Exception {
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(false);
        // 700_000_000 ns 대기 → ceiling(0.7) = 1 초.
        when(probe.getNanosToWaitForRefill()).thenReturn(700_000_000L);
        when(probe.getRemainingTokens()).thenReturn(0L);
        when(rateLimiter.tryConsume(eq(1001L))).thenReturn(probe);

        MockHttpServletRequest req = requestWithUser("1001");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("1");
        assertThat(res.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(res.getContentType()).contains("application/json");
        String body = res.getContentAsString();
        assertThat(body).contains("RATE_LIMIT_EXCEEDED");
        assertThat(body).contains("\"success\":false");

        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Retry-After 가 1 초 미만이어도 ceiling 으로 1 초 (소수점 올림)")
    void retry_after_rounds_up_to_at_least_1_second() throws Exception {
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(false);
        when(probe.getNanosToWaitForRefill()).thenReturn(1L);  // 1ns
        when(probe.getRemainingTokens()).thenReturn(0L);
        when(rateLimiter.tryConsume(any(Long.class))).thenReturn(probe);

        MockHttpServletRequest req = requestWithUser("1001");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    @DisplayName("Retry-After 가 정확히 N 초인 경우 N (ceiling 영향 없음)")
    void retry_after_exact_seconds() throws Exception {
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(false);
        when(probe.getNanosToWaitForRefill()).thenReturn(3_000_000_000L);  // 정확히 3초
        when(probe.getRemainingTokens()).thenReturn(0L);
        when(rateLimiter.tryConsume(any(Long.class))).thenReturn(probe);

        MockHttpServletRequest req = requestWithUser("1001");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader("Retry-After")).isEqualTo("3");
    }
}
