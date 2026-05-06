package com.promotion.servera.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.promotion.common.coupon.IssueResult;
import com.promotion.common.coupon.IssueStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class RestClientCouponIssuingClientTest {

    private static final String INTERNAL_URI = "http://server-b/internal/v1/coupons/issue";

    private MockRestServiceServer server;
    private RestClientCouponIssuingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://server-b");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientCouponIssuingClient(builder.build());
    }

    @Test
    @DisplayName("정상 200 + status ISSUED → IssueResult.issued(couponCode)")
    void issued_response_maps_to_IssueResult_issued() {
        server.expect(requestTo(INTERNAL_URI))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "{\"status\":\"ISSUED\",\"couponCode\":\"ABCD12345678\"}",
                MediaType.APPLICATION_JSON));

        IssueResult result = client.issue(1L, 100L, "idem-1");

        assertThat(result.status()).isEqualTo(IssueStatus.ISSUED);
        assertThat(result.couponCode().value()).isEqualTo("ABCD12345678");
    }

    @Test
    @DisplayName("ALREADY_ISSUED → IssueResult.alreadyIssued (캐시 hit 시 server-b 가 같은 코드 반환)")
    void already_issued_response() {
        server.expect(requestTo(INTERNAL_URI))
            .andRespond(withSuccess(
                "{\"status\":\"ALREADY_ISSUED\",\"couponCode\":\"CACHED123456\"}",
                MediaType.APPLICATION_JSON));

        IssueResult result = client.issue(1L, 100L, "idem-1");

        assertThat(result.status()).isEqualTo(IssueStatus.ALREADY_ISSUED);
        assertThat(result.couponCode().value()).isEqualTo("CACHED123456");
    }

    @Test
    @DisplayName("SOLD_OUT → IssueResult.soldOut")
    void sold_out_response() {
        server.expect(requestTo(INTERNAL_URI))
            .andRespond(withSuccess(
                "{\"status\":\"SOLD_OUT\",\"couponCode\":null,\"failureReason\":\"stock exhausted\"}",
                MediaType.APPLICATION_JSON));

        IssueResult result = client.issue(1L, 100L, "idem-1");

        assertThat(result.status()).isEqualTo(IssueStatus.SOLD_OUT);
        assertThat(result.couponCode()).isNull();
    }

    @Test
    @DisplayName("INTERNAL_ERROR + failureReason → IssueResult.internalError(reason)")
    void internal_error_response_with_reason_propagates() {
        server.expect(requestTo(INTERNAL_URI))
            .andRespond(withSuccess(
                "{\"status\":\"INTERNAL_ERROR\",\"couponCode\":null,\"failureReason\":\"compensated\"}",
                MediaType.APPLICATION_JSON));

        IssueResult result = client.issue(1L, 100L, "idem-1");

        assertThat(result.status()).isEqualTo(IssueStatus.INTERNAL_ERROR);
        assertThat(result.failureReason()).isEqualTo("compensated");
    }

    @Test
    @DisplayName("빈 body → IssueResult.internalError(\"empty-response\")")
    void empty_body_maps_to_empty_response() {
        server.expect(requestTo(INTERNAL_URI))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        IssueResult result = client.issue(1L, 100L, "idem-1");

        assertThat(result.status()).isEqualTo(IssueStatus.INTERNAL_ERROR);
        assertThat(result.failureReason()).isEqualTo("empty-response");
    }

    @Test
    @DisplayName("fallback — CallNotPermittedException → INTERNAL_ERROR(circuit-open)")
    void fallback_circuit_open() {
        // Resilience4j 의 CallNotPermittedException 은 정적 팩토리로 생성.
        CircuitBreaker cb = CircuitBreaker.of("test", CircuitBreakerConfig.ofDefaults());
        cb.transitionToOpenState();
        Throwable cause = CallNotPermittedException.createCallNotPermittedException(cb);

        IssueResult result = client.issueFallback(1L, 100L, "idem-1", cause);

        assertThat(result.status()).isEqualTo(IssueStatus.INTERNAL_ERROR);
        assertThat(result.failureReason()).isEqualTo("circuit-open");
    }

    @Test
    @DisplayName("fallback — RestClientResponseException → INTERNAL_ERROR(server-b-status:N)")
    void fallback_server_b_5xx() {
        // 5xx 응답을 RestClient 가 RestClientResponseException 으로 throw.
        server.expect(requestTo(INTERNAL_URI))
            .andRespond(withServerError().body("{\"error\":\"boom\"}").contentType(MediaType.APPLICATION_JSON));

        // CB 우회를 위해 fallback 직접 호출 — 실 호출에서 발생할 RestClientResponseException 시뮬.
        Throwable cause;
        try {
            client.issue(1L, 100L, "idem-fallback");
            throw new AssertionError("expected RestClientResponseException");
        } catch (RestClientResponseException ex) {
            cause = ex;
        }

        IssueResult result = client.issueFallback(1L, 100L, "idem-1", cause);

        assertThat(result.status()).isEqualTo(IssueStatus.INTERNAL_ERROR);
        assertThat(result.failureReason()).isEqualTo("server-b-status:500");
    }

    @Test
    @DisplayName("fallback — 일반 RuntimeException (timeout / connection refused) → downstream-error:..")
    void fallback_generic_runtime_exception() {
        Throwable cause = new ResourceAccessException(
            "I/O error", new SocketTimeoutException("read timed out"));

        IssueResult result = client.issueFallback(1L, 100L, "idem-1", cause);

        assertThat(result.status()).isEqualTo(IssueStatus.INTERNAL_ERROR);
        assertThat(result.failureReason()).isEqualTo("downstream-error:ResourceAccessException");
    }
}
