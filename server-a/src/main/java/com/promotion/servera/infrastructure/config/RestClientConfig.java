package com.promotion.servera.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** Server B 호출 전용 RestClient (ADR-001). */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient couponIssuingRestClient(
            @Value("${app.server-b.base-url}") String baseUrl,
            @Value("${app.server-b.connect-timeout-millis:1000}") long connectTimeoutMs,
            @Value("${app.server-b.read-timeout-millis:500}") long readTimeoutMs
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
