package com.promotion.servera.api.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.promotion.servera.infrastructure.idempotency.CachedResponse;
import com.promotion.servera.infrastructure.idempotency.IdempotencyKey;
import com.promotion.servera.infrastructure.idempotency.IdempotencyStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IdempotencyFilterTest {

    private IdempotencyStore store;
    private IdempotencyFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStore.class);
        chain = mock(FilterChain.class);
        filter = new IdempotencyFilter(store);
    }

    private MockHttpServletRequest requestWith(String userId, String idemKey) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/coupons/issue-requests");
        if (userId != null) req.addHeader("X-User-Id", userId);
        if (idemKey != null) req.addHeader("Idempotency-Key", idemKey);
        return req;
    }

    @Test
    @DisplayName("X-User-Id 누락 → store 조회 없이 chain 위임 (Controller 가 400 처리)")
    void missing_user_id_passes_through() throws Exception {
        MockHttpServletRequest req = requestWith(null, "idem-1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verify(store, never()).find(any());
    }

    @Test
    @DisplayName("Idempotency-Key 누락 → store 조회 없이 chain 위임")
    void missing_idem_key_passes_through() throws Exception {
        MockHttpServletRequest req = requestWith("1001", null);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verify(store, never()).find(any());
    }

    @Test
    @DisplayName("X-User-Id 가 숫자 아님 → 통과 (Controller 가 400)")
    void non_numeric_user_id_passes_through() throws Exception {
        MockHttpServletRequest req = requestWith("not-a-number", "idem-1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verify(store, never()).find(any());
    }

    @Test
    @DisplayName("캐시 hit → 저장된 status + body 재현 + chain 호출 안 함")
    void cache_hit_replays_cached_response() throws Exception {
        when(store.find(eq(new IdempotencyKey(1001L, "idem-1"))))
            .thenReturn(Optional.of(new CachedResponse(200, "{\"cached\":true}")));

        MockHttpServletRequest req = requestWith("1001", "idem-1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).isEqualTo("{\"cached\":true}");
        verify(chain, never()).doFilter(any(), any());
        verify(store, never()).save(any(), any());
    }

    @Test
    @DisplayName("캐시 miss + 2xx 응답 → save 호출 (응답 캐싱)")
    void cache_miss_then_2xx_saves_response() throws Exception {
        when(store.find(any())).thenReturn(Optional.empty());

        MockHttpServletRequest req = requestWith("1001", "idem-1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        // Mock chain that writes a 2xx response.
        FilterChain writingChain = (request, response) -> {
            HttpServletResponse r = (HttpServletResponse) response;
            r.setStatus(200);
            r.setContentType("application/json");
            r.getWriter().write("{\"data\":\"new\"}");
        };

        filter.doFilter(req, res, writingChain);

        verify(store, times(1))
            .save(eq(new IdempotencyKey(1001L, "idem-1")), any(CachedResponse.class));
        // 본문이 클라이언트로 정상 복사됨
        assertThat(res.getContentAsString()).contains("\"new\"");
    }

    @Test
    @DisplayName("캐시 miss + 4xx 응답 → save 안 함 (재시도 가능 상태 보존)")
    void cache_miss_then_4xx_does_not_save() throws Exception {
        when(store.find(any())).thenReturn(Optional.empty());

        MockHttpServletRequest req = requestWith("1001", "idem-1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain writingChain = (request, response) -> {
            HttpServletResponse r = (HttpServletResponse) response;
            r.setStatus(400);
            r.getWriter().write("{\"error\":\"bad\"}");
        };

        filter.doFilter(req, res, writingChain);

        verify(store, never()).save(any(), any());
    }

    @Test
    @DisplayName("캐시 miss + 5xx 응답 → save 안 함")
    void cache_miss_then_5xx_does_not_save() throws Exception {
        when(store.find(any())).thenReturn(Optional.empty());

        MockHttpServletRequest req = requestWith("1001", "idem-1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain writingChain = (request, response) -> {
            HttpServletResponse r = (HttpServletResponse) response;
            r.setStatus(503);
        };

        filter.doFilter(req, res, writingChain);

        verify(store, never()).save(any(), any());
    }

    @Test
    @DisplayName("store.find 가 RuntimeException → cache miss 로 처리 (Redis 장애 fail-safe)")
    void store_find_failure_is_treated_as_miss() throws Exception {
        when(store.find(any())).thenThrow(new RuntimeException("redis down"));

        MockHttpServletRequest req = requestWith("1001", "idem-1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain writingChain = (request, response) -> {
            ((HttpServletResponse) response).setStatus(200);
        };

        // 예외가 filter 밖으로 나가지 않고 chain 이 정상 진행되어야 함.
        boolean[] chainCalled = {false};
        FilterChain spyChain = (request, response) -> {
            chainCalled[0] = true;
            ((HttpServletResponse) response).setStatus(200);
        };
        filter.doFilter(req, res, spyChain);

        assertThat(chainCalled[0]).as("store 실패 시에도 chain 진행").isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }
}
