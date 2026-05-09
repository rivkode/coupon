package com.promotion.serverb.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

/** Server C 의 internal GET 호출 (PendingIssueScheduler 가 사용). */
@Configuration
public class UserCouponClient {

    @Bean
    public RestClient serverCRestClient(
            @Value("${app.server-c.base-url:http://localhost:8082}") String baseUrl,
            @Value("${app.server-c.timeout-ms:1500}") long timeoutMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Math.min(timeoutMs, 1_000));
        factory.setReadTimeout((int) timeoutMs);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Bean
    public Lookup userCouponLookup(RestClient serverCRestClient) {
        return new Lookup(serverCRestClient);
    }

    public static class Lookup {
        private final RestClient restClient;

        public Lookup(RestClient restClient) {
            this.restClient = restClient;
        }

        public Optional<Result> findOne(long userId, long couponTypeId) {
            try {
                Envelope envelope = restClient.get()
                        .uri("/internal/v1/users/{userId}/coupons/{couponTypeId}", userId, couponTypeId)
                        .retrieve()
                        .body(Envelope.class);
                if (envelope == null || envelope.data() == null) {
                    return Optional.empty();
                }
                return Optional.of(envelope.data());
            } catch (HttpClientErrorException.NotFound nf) {
                return Optional.empty();
            }
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Envelope(boolean success, Result data) {}

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Result(
                @JsonProperty("userId") long userId,
                @JsonProperty("eventId") long eventId,
                @JsonProperty("couponTypeId") long couponTypeId,
                @JsonProperty("code") String code,
                @JsonProperty("status") String status
        ) {}
    }

    @SuppressWarnings("unused")
    static Duration unusedDurationToAvoidImportElision() {
        return Duration.ZERO;
    }
}
