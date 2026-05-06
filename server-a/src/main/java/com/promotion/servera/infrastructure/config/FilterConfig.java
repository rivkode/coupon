package com.promotion.servera.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.servera.api.filter.IdempotencyFilter;
import com.promotion.servera.api.filter.RateLimitFilter;
import com.promotion.servera.infrastructure.idempotency.IdempotencyStore;
import com.promotion.servera.infrastructure.ratelimit.UserRateLimiter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Filter chain 등록.
 *
 * <p>실행 순서:
 * <ol>
 *   <li>{@link RateLimitFilter} (Order = HIGHEST_PRECEDENCE) — 가장 먼저 차단 결정</li>
 *   <li>{@link IdempotencyFilter} (Order = HIGHEST_PRECEDENCE + 10) — 멱등 캐시 hit 빠른 반환</li>
 *   <li>Controller</li>
 * </ol>
 *
 * <p>두 필터 모두 {@code /api/*} 경로에만 적용 — actuator, error 페이지는 우회.
 */
@Configuration
public class FilterConfig {

    private static final String API_PATH = "/api/*";

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
        UserRateLimiter rateLimiter, ObjectMapper objectMapper
    ) {
        FilterRegistrationBean<RateLimitFilter> reg =
            new FilterRegistrationBean<>(new RateLimitFilter(rateLimiter, objectMapper));
        reg.addUrlPatterns(API_PATH);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("rateLimitFilter");
        return reg;
    }

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
        IdempotencyStore idempotencyStore
    ) {
        FilterRegistrationBean<IdempotencyFilter> reg =
            new FilterRegistrationBean<>(new IdempotencyFilter(idempotencyStore));
        reg.addUrlPatterns(API_PATH);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setName("idempotencyFilter");
        return reg;
    }
}
