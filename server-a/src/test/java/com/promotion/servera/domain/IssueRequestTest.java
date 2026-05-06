package com.promotion.servera.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promotion.common.coupon.CouponCode;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IssueRequestTest {

    private static final Instant NOW = Instant.parse("2026-05-07T00:00:00Z");
    private static final CouponCode CODE = new CouponCode("ABCD12345678");

    @Nested
    @DisplayName("received")
    class Received {

        @Test
        void initial_status_is_RECEIVED_with_uuid_requestId() {
            IssueRequest req = IssueRequest.received(1L, 100L, "idem-1", NOW);

            assertThat(req.getStatus()).isEqualTo(IssueRequestStatus.RECEIVED);
            assertThat(req.getUserId()).isEqualTo(1L);
            assertThat(req.getEventId()).isEqualTo(100L);
            assertThat(req.getIdempotencyKey()).isEqualTo("idem-1");
            assertThat(req.getRequestId()).matches("^[0-9a-f-]{36}$");
            assertThat(req.getCouponCode()).isNull();
            assertThat(req.getFailureReason()).isNull();
        }

        @Test
        void rejects_null_userId() {
            assertThatThrownBy(() -> IssueRequest.received(null, 100L, "idem", NOW))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejects_null_idempotencyKey() {
            assertThatThrownBy(() -> IssueRequest.received(1L, 100L, null, NOW))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("transitions — RECEIVED → FORWARDED → SUCCEEDED")
    class HappyPath {

        @Test
        void forward_then_succeed_with_couponCode() {
            IssueRequest req = IssueRequest.received(1L, 100L, "idem", NOW);
            req.markForwarded();
            req.markSucceeded(CODE);

            assertThat(req.getStatus()).isEqualTo(IssueRequestStatus.SUCCEEDED);
            assertThat(req.getCouponCode()).isEqualTo(CODE);
            assertThat(req.getFailureReason()).isNull();
        }

        @Test
        void forward_then_fail_with_reason() {
            IssueRequest req = IssueRequest.received(1L, 100L, "idem", NOW);
            req.markForwarded();
            req.markFailed("stock exhausted");

            assertThat(req.getStatus()).isEqualTo(IssueRequestStatus.FAILED);
            assertThat(req.getFailureReason()).isEqualTo("stock exhausted");
            assertThat(req.getCouponCode()).isNull();
        }
    }

    @Nested
    @DisplayName("invalid transitions")
    class InvalidTransitions {

        @Test
        void cannot_succeed_directly_from_RECEIVED() {
            IssueRequest req = IssueRequest.received(1L, 100L, "idem", NOW);
            assertThatThrownBy(() -> req.markSucceeded(CODE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECEIVED → SUCCEEDED");
        }

        @Test
        void cannot_fail_directly_from_RECEIVED() {
            IssueRequest req = IssueRequest.received(1L, 100L, "idem", NOW);
            assertThatThrownBy(() -> req.markFailed("reason"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void cannot_re_transition_from_terminal_SUCCEEDED() {
            IssueRequest req = IssueRequest.received(1L, 100L, "idem", NOW);
            req.markForwarded();
            req.markSucceeded(CODE);

            assertThatThrownBy(req::markForwarded).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> req.markFailed("late")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> req.reject("late")).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void cannot_reject_after_FORWARDED() {
            IssueRequest req = IssueRequest.received(1L, 100L, "idem", NOW);
            req.markForwarded();
            assertThatThrownBy(() -> req.reject("idem-conflict"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void markSucceeded_rejects_null_couponCode() {
            IssueRequest req = IssueRequest.received(1L, 100L, "idem", NOW);
            req.markForwarded();
            assertThatThrownBy(() -> req.markSucceeded(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("reject — RECEIVED → REJECTED (Idempotency / RateLimit / Validation 즉시 거절)")
    class Reject {

        @Test
        void rejects_with_reason() {
            IssueRequest req = IssueRequest.received(1L, 100L, "idem", NOW);
            req.reject("rate-limit-exceeded");

            assertThat(req.getStatus()).isEqualTo(IssueRequestStatus.REJECTED);
            assertThat(req.getFailureReason()).isEqualTo("rate-limit-exceeded");
        }
    }
}
