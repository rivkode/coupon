package com.promotion.servera.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.servera.api.dto.ApiResponse;
import com.promotion.servera.api.dto.ErrorResponse;
import com.promotion.servera.infrastructure.ratelimit.UserRateLimiter;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 사용자별 Token Bucket Rate Limiter (CLAUDE.md ADR-005).
 *
 * <p>Filter chain 순서: RateLimit → Idempotency → Controller. RateLimit 을 먼저 두는 이유는
 * 차단 결정을 가장 빠르게 내려 downstream 비용을 줄이기 위함.
 *
 * <p>X-User-Id 헤더가 없거나 숫자로 변환 불가하면 차단하지 않고 통과 — 헤더 검증은
 * Controller 레벨에서 일관되게 (MISSING_HEADER 400) 처리. RateLimit 은 인증된 사용자만 카운트.
 *
 * <p>거절 응답:
 * <ul>
 *   <li>HTTP 429 Too Many Requests</li>
 *   <li>{@code Retry-After} 헤더 — 토큰 회복까지 남은 초 (반올림 올림)</li>
 *   <li>{@code X-RateLimit-Remaining} — 남은 토큰 수</li>
 *   <li>JSON body — {@code ApiResponse.failure(RATE_LIMIT_EXCEEDED)}</li>
 * </ul>
 */
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final UserRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

        Long userId = parseUserId(request.getHeader(USER_ID_HEADER));
        if (userId == null) {
            chain.doFilter(request, response);
            return;
        }

        ConsumptionProbe probe = rateLimiter.tryConsume(userId);
        if (!probe.isConsumed()) {
            long retryAfterSec = (probe.getNanosToWaitForRefill() + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND;
            log.info("rate limit exceeded userId={} retryAfter={}s", userId, retryAfterSec);
            writeRejectResponse(response, retryAfterSec, probe.getRemainingTokens());
            return;
        }

        response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
        chain.doFilter(request, response);
    }

    private Long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void writeRejectResponse(HttpServletResponse response, long retryAfterSec, long remaining)
        throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSec));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ErrorResponse error = ErrorResponse.of(
            "RATE_LIMIT_EXCEEDED",
            "too many requests; retry after %d second(s)".formatted(retryAfterSec));
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(error));
    }
}
