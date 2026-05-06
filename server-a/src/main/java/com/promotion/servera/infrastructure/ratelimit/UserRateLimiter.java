package com.promotion.servera.infrastructure.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 사용자별 토큰 버킷 (CLAUDE.md ADR-005).
 *
 * <p>Token Bucket 알고리즘: 사용자당 최대 {@code capacity} 토큰을 보유하며 매
 * {@code refillPeriod} 마다 {@code refillTokens} 만큼 충전. 버스트 허용 + 평균 rate 제한.
 *
 * <p>버킷 키는 {@code rate:user:{userId}}. Lettuce-backed 분산 백엔드(Bucket4jConfig) 사용
 * → 멀티 인스턴스 환경에서도 일관 적용.
 */
@Component
public class UserRateLimiter {

    private static final String KEY_PREFIX = "rate:user:";

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration bucketConfiguration;

    public UserRateLimiter(
        ProxyManager<String> proxyManager,
        @Value("${app.rate-limit.user.capacity:10}") long capacity,
        @Value("${app.rate-limit.user.refill-tokens:10}") long refillTokens,
        @Value("${app.rate-limit.user.refill-period-seconds:1}") long refillPeriodSeconds
    ) {
        this.proxyManager = proxyManager;
        this.bucketConfiguration = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(refillTokens, Duration.ofSeconds(refillPeriodSeconds))
                .build())
            .build();
    }

    public ConsumptionProbe tryConsume(long userId) {
        BucketProxy bucket = proxyManager.getProxy(KEY_PREFIX + userId, () -> bucketConfiguration);
        return bucket.tryConsumeAndReturnRemaining(1);
    }
}
