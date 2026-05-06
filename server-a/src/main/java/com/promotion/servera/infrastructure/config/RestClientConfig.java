package com.promotion.servera.infrastructure.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Server B 호출 전용 RestClient. CLAUDE.md ADR-001: A→B 호출은 동기 + connect 1s + read 200ms.
 *
 * <p>Resilience4j {@code @TimeLimiter} 는 {@code CompletableFuture} 반환을 요구해 동기
 * 인터페이스(`CouponIssuingClient`) 를 깨야 한다. 대신 HTTP 클라이언트 레벨 read-timeout 으로
 * 동일한 효과(200ms 초과 시 IOException)를 얻고 {@code @CircuitBreaker} 가 그 실패를 카운트하게 한다.
 *
 * <p>{@code @Profile("!local")} — 로컬 stub 환경에서는 빈 자체를 만들지 않아 RestClient 도 필요 없음.
 */
@Configuration
@Profile("!local")
public class RestClientConfig {

    @Bean
    public RestClient couponIssuingRestClient(
        @Value("${app.server-b.base-url}") String baseUrl,
        @Value("${app.server-b.connect-timeout-millis:1000}") long connectTimeoutMs,
        @Value("${app.server-b.read-timeout-millis:200}") long readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .build();
    }
}
