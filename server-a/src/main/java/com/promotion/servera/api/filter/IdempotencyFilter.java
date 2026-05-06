package com.promotion.servera.api.filter;

import com.promotion.servera.infrastructure.idempotency.CachedResponse;
import com.promotion.servera.infrastructure.idempotency.IdempotencyKey;
import com.promotion.servera.infrastructure.idempotency.IdempotencyStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Idempotency-Key 기반 응답 캐싱 필터 (CLAUDE.md ADR-004).
 *
 * <p>Filter chain 순서: RateLimit → <b>Idempotency</b> → Controller.
 *
 * <p>동작:
 * <ol>
 *   <li>{@code X-User-Id} / {@code Idempotency-Key} 둘 중 하나라도 누락 → 통과 (Controller 가 400)</li>
 *   <li>Redis 에 캐시된 응답이 있으면 그대로 재현 후 종료</li>
 *   <li>없으면 응답을 ContentCachingResponseWrapper 로 캡처 → chain 진행</li>
 *   <li>응답 status 가 2xx 일 때만 캐시 저장. 4xx/5xx 는 재시도 가능하도록 캐시하지 않음.</li>
 * </ol>
 *
 * <p>Race 처리는 의도적으로 단순화 ("응답 캐싱 후 재현"). 동시 첫 요청 시 두 요청 모두 downstream
 * 으로 가지만 Server C 의 {@code coupon.idempotency_key} UNIQUE 가 최종 차단 — kickoff Phase 7 권고.
 */
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final IdempotencyStore idempotencyStore;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

        Long userId = parseUserId(request.getHeader(USER_ID_HEADER));
        String rawKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (userId == null || rawKey == null || rawKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        IdempotencyKey key;
        try {
            key = new IdempotencyKey(userId, rawKey);
        } catch (IllegalArgumentException ex) {
            chain.doFilter(request, response);
            return;
        }

        Optional<CachedResponse> cached = safeFind(key);
        if (cached.isPresent()) {
            replay(response, cached.get());
            log.debug("idempotency cache hit: redisKey={}", key.redisKey());
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapper);

        try {
            int status = wrapper.getStatus();
            if (HttpStatusCode.valueOf(status).is2xxSuccessful()) {
                byte[] body = wrapper.getContentAsByteArray();
                idempotencyStore.save(key, new CachedResponse(status, new String(body, StandardCharsets.UTF_8)));
            }
        } finally {
            wrapper.copyBodyToResponse();
        }
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

    private Optional<CachedResponse> safeFind(IdempotencyKey key) {
        try {
            return idempotencyStore.find(key);
        } catch (RuntimeException ex) {
            log.warn("idempotency store lookup failed (treating as miss): redisKey={} reason={}",
                key.redisKey(), ex.getMessage());
            return Optional.empty();
        }
    }

    private void replay(HttpServletResponse response, CachedResponse cached) throws IOException {
        response.setStatus(cached.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(cached.body());
    }
}
