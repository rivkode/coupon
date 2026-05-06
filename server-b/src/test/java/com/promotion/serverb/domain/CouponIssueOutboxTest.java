package com.promotion.serverb.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promotion.common.coupon.CouponCode;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CouponIssueOutboxTest {

    private static final CouponCode CODE = new CouponCode("ABCD12345678");
    private static final String IDEM_KEY = "11111111-2222-3333-4444-555555555555";
    private static final Instant T0 = Instant.parse("2026-05-06T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-06T10:00:01Z");
    private static final CouponIssuedEvent EVENT = new CouponIssuedEvent(
        1L, 42L, CODE, IDEM_KEY, T0
    );

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        void published_false_with_null_publishedAt() {
            CouponIssueOutbox outbox = CouponIssueOutbox.create(EVENT, T0);

            assertThat(outbox.isPublished()).isFalse();
            assertThat(outbox.getPublishedAt()).isNull();
            assertThat(outbox.getCreatedAt()).isEqualTo(T0);
            assertThat(outbox.getUpdatedAt()).isEqualTo(T0);
            assertThat(outbox.couponCode()).isEqualTo(CODE);
            assertThat(outbox.idempotencyKey()).isEqualTo(IDEM_KEY);
        }
    }

    @Nested
    @DisplayName("markPublished")
    class MarkPublished {

        @Test
        void sets_published_true_and_publishedAt() {
            CouponIssueOutbox outbox = CouponIssueOutbox.create(EVENT, T0);

            outbox.markPublished(T1);

            assertThat(outbox.isPublished()).isTrue();
            assertThat(outbox.getPublishedAt()).isEqualTo(T1);
        }

        @Test
        void rejects_double_publish() {
            // poller 가 한 행을 두 번 처리하지 못하도록 — 멱등 발행은 호출자가 published=true 행을 스킵하는 방식.
            CouponIssueOutbox outbox = CouponIssueOutbox.create(EVENT, T0);
            outbox.markPublished(T1);

            assertThatThrownBy(() -> outbox.markPublished(T1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already published");
        }
    }

    @Nested
    @DisplayName("reconstitute")
    class Reconstitute {

        @Test
        void rejects_published_true_with_null_publishedAt() {
            assertThatThrownBy(() -> CouponIssueOutbox.reconstitute(
                1L, EVENT, true, null, 0L, T0, T0
            )).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void rejects_published_false_with_publishedAt() {
            assertThatThrownBy(() -> CouponIssueOutbox.reconstitute(
                1L, EVENT, false, T1, 0L, T0, T0
            )).isInstanceOf(IllegalStateException.class);
        }
    }
}
