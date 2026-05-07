package com.promotion.serverc.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.common.coupon.CouponCode;
import com.promotion.common.coupon.CouponIssuedEventPayload;
import com.promotion.serverc.domain.CouponRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Server C 의 Kafka consumer 통합 검증 — EmbeddedKafka + host MySQL.
 *
 * <p>테스트 의도:
 * <ul>
 *   <li>실 KafkaTemplate 발행 → consumer 수신 → DB 영속의 e2e 흐름 검증.</li>
 *   <li>중복 메시지 (UNIQUE 위반) 가 ack-and-skip 으로 흡수되는지 (의미적 exactly-once).</li>
 *   <li>다른 user 가 같은 idem 을 써도 user-scoped UNIQUE 가 정합 (PR #12 + V2 마이그레이션).</li>
 * </ul>
 *
 * <p>EmbeddedKafka 사용 — server-b 의 OutboxPollerIT 가 host docker-compose Kafka 를 쓰는 것과
 * 정책이 다른 이유: 본 IT 의 의도가 "실 Kafka 메시지 → consume → DB 영속" 의 전 흐름이므로 broker
 * 가 실제로 필요. EmbeddedKafka 는 사전 docker compose up 없이 ./gradlew test 한 줄로 통과 — CI 친화.
 *
 * <p><b>사전 조건</b>: docker-compose 의 mysql 만 (Kafka 는 in-process embedded).
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 3,
    topics = {"coupon.issued"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Sql(statements = "DELETE FROM coupon", executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class CouponConsumerIT {

    private static final String TOPIC = "coupon.issued";

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private CouponRepository couponRepository;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("정상 메시지 1건 → DB 1행 영속 + code/idem/userId 일치")
    void publish_persists_coupon() throws JsonProcessingException {
        CouponIssuedEventPayload payload = newPayload(1L, 4242L, "ABCDEFGHJKMN", "idem-1");

        publish(payload);

        await().atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() ->
                assertThat(couponRepository.findByCode(new CouponCode(payload.couponCode())))
                    .isPresent()
                    .hasValueSatisfying(c -> {
                        assertThat(c.getUserId()).isEqualTo(4242L);
                        assertThat(c.getEventId()).isEqualTo(1L);
                        assertThat(c.getIdempotencyKey()).isEqualTo("idem-1");
                        assertThat(c.getUsedAt()).isNull();
                    })
            );
    }

    @Test
    @DisplayName("같은 (userId, idem) 두 번 publish → DB 1행만 (UNIQUE 흡수, 의미적 exactly-once)")
    void duplicate_message_results_in_single_row() throws JsonProcessingException {
        CouponIssuedEventPayload payload = newPayload(1L, 5555L, "MNPQRSTVWXYZ", "idem-dup");

        publish(payload);
        publish(payload);

        // 첫 메시지가 영속될 때까지 대기 + 두 번째가 처리될 추가 시간 확보.
        await().atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() ->
                assertThat(couponRepository.findByCode(new CouponCode(payload.couponCode())))
                    .isPresent()
            );
        // 두 번째 메시지가 흡수됐는지 — userId 의 쿠폰 수가 1이어야 함.
        await().during(Duration.ofMillis(500))
            .atMost(Duration.ofSeconds(2))
            .untilAsserted(() ->
                assertThat(couponRepository.findByUserId(5555L)).hasSize(1)
            );
    }

    @Test
    @DisplayName("다른 user 가 같은 idem → 두 행 모두 영속 (user-scoped UNIQUE 정합)")
    void different_users_same_idem_creates_two_rows() throws JsonProcessingException {
        String sharedIdem = "idem-shared-" + UUID.randomUUID();
        CouponIssuedEventPayload p1 = newPayload(1L, 7001L, "AAAAAAAAAAAA", sharedIdem);
        CouponIssuedEventPayload p2 = newPayload(1L, 7002L, "BBBBBBBBBBBB", sharedIdem);

        publish(p1);
        publish(p2);

        await().atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> {
                assertThat(couponRepository.findByCode(new CouponCode("AAAAAAAAAAAA"))).isPresent();
                assertThat(couponRepository.findByCode(new CouponCode("BBBBBBBBBBBB"))).isPresent();
            });
        assertThat(couponRepository.findByUserId(7001L)).hasSize(1);
        assertThat(couponRepository.findByUserId(7002L)).hasSize(1);
    }

    private void publish(CouponIssuedEventPayload payload) throws JsonProcessingException {
        kafkaTemplate.send(TOPIC, String.valueOf(payload.userId()),
            objectMapper.writeValueAsString(payload));
    }

    private static CouponIssuedEventPayload newPayload(
        long eventId, long userId, String code, String idem) {
        return new CouponIssuedEventPayload(
            eventId, userId, code, idem, Instant.parse("2026-05-07T10:00:00Z"));
    }
}
