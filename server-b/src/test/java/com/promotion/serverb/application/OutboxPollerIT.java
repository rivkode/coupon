package com.promotion.serverb.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.promotion.common.coupon.CouponCode;
import com.promotion.serverb.domain.CouponIssueOutbox;
import com.promotion.serverb.domain.CouponIssueOutboxRepository;
import com.promotion.serverb.domain.CouponIssuedEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Outbox poller 통합 검증 — Repository SKIP LOCKED native query + 트랜잭션 경계 + markPublished 흐름.
 *
 * <p>Kafka publisher 는 {@link MockitoBean} 으로 대체 (운영 KafkaTemplate 와 격리). real Kafka 까지
 * 가는 e2e 검증은 PR #17 (k6 day3 시나리오) 의 책임.
 *
 * <p><b>사전 조건</b>: docker-compose 의 mysql 만 (Redis / Kafka 불필요).
 *
 * <p><b>scheduler 비활성화</b>: {@code app.outbox.poller.fixed-delay-ms=3600000} 으로 자동 cycle
 * 사실상 disable. 테스트는 {@link OutboxPoller#pollOnce()} 를 명시적으로 호출.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
    // 자동 cycle 비활성화 — 부팅 후 1시간 뒤에야 첫 cycle. 테스트는 pollOnce() 명시 호출.
    "app.outbox.poller.fixed-delay-ms=3600000",
    "app.outbox.poller.initial-delay-ms=3600000"
})
class OutboxPollerIT {

    @Autowired private CouponIssueOutboxRepository outboxRepository;
    @Autowired private OutboxPoller poller;

    @MockitoBean private CouponIssuedEventPublisher publisher;

    @BeforeEach
    void cleanup() {
        outboxRepository.deleteAll();
    }

    @Test
    @DisplayName("미발행 행이 published=true 로 마킹되고 publisher 가 행별 호출됨")
    void publishes_unpublished_rows_and_marks() {
        CouponIssueOutbox o1 = saveUnpublished("user-1");
        CouponIssueOutbox o2 = saveUnpublished("user-2");
        doNothing().when(publisher).publish(any());

        int published = poller.pollOnce();

        assertThat(published).isEqualTo(2);
        verify(publisher, times(2)).publish(any(CouponIssueOutbox.class));

        // DB 상태 검증 — 둘 다 published=true 로 마킹
        assertThat(outboxRepository.findByCouponCode(o1.couponCode()))
            .hasValueSatisfying(o -> {
                assertThat(o.isPublished()).isTrue();
                assertThat(o.getPublishedAt()).isNotNull();
            });
        assertThat(outboxRepository.findByCouponCode(o2.couponCode()))
            .hasValueSatisfying(o -> {
                assertThat(o.isPublished()).isTrue();
                assertThat(o.getPublishedAt()).isNotNull();
            });
    }

    @Test
    @DisplayName("publish 실패 행은 published=false 로 남아 다음 cycle 재시도 가능")
    void publish_failure_keeps_row_unpublished() {
        CouponIssueOutbox o1 = saveUnpublished("user-fail");
        doThrow(new CouponIssuedEventPublishException("simulated", new RuntimeException()))
            .when(publisher).publish(any());

        int published = poller.pollOnce();

        assertThat(published).isZero();
        assertThat(outboxRepository.findByCouponCode(o1.couponCode()))
            .hasValueSatisfying(o -> {
                assertThat(o.isPublished()).isFalse();
                assertThat(o.getPublishedAt()).isNull();
            });
    }

    @Test
    @DisplayName("이미 published=true 인 행은 SKIP LOCKED 쿼리에서 제외 — publisher 호출 0회")
    void already_published_rows_are_skipped() {
        // 이미 published=true 인 행만 INSERT
        CouponIssueOutbox alreadyPublished = CouponIssueOutbox.reconstitute(
            null,
            newEvent("user-already-published"),
            true,
            Instant.now(),
            0L,
            Instant.now(),
            Instant.now()
        );
        outboxRepository.save(alreadyPublished);

        int published = poller.pollOnce();

        assertThat(published).isZero();
        verify(publisher, times(0)).publish(any());
    }

    private CouponIssueOutbox saveUnpublished(String userTag) {
        CouponIssueOutbox o = CouponIssueOutbox.create(newEvent(userTag), Instant.now());
        return outboxRepository.save(o);
    }

    private CouponIssuedEvent newEvent(String userTag) {
        // userId 가 다르도록 hash. idem 은 UUID 라 user-scoped UNIQUE 충돌 안 함.
        long userId = Math.abs(userTag.hashCode()) + 1L;
        return new CouponIssuedEvent(
            1L,
            userId,
            CouponCode.generate(),
            UUID.randomUUID().toString(),
            Instant.now()
        );
    }
}
