package com.promotion.serverb.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.common.coupon.CouponIssuedEventPayload;
import com.promotion.serverb.application.CouponIssuedEventPublishException;
import com.promotion.serverb.application.CouponIssuedEventPublisher;
import com.promotion.serverb.domain.CouponIssueOutbox;
import com.promotion.serverb.domain.CouponIssuedEvent;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Outbox 행을 Kafka {@code coupon.issued} 토픽으로 동기 발행 (CLAUDE.md ADR-002).
 *
 * <p>발행 보장: producer 가 {@code acks=all + enable.idempotence=true} 로 설정되어
 * broker 측 retry dedup 까지 보장. end-to-end exactly-once 는 Server C 의 UNIQUE constraint
 * 가 권위 (의미적 exactly-once).
 *
 * <p>{@code .get(timeout)} 으로 동기 블로킹. send 가 timeout / broker 오류로 실패하면
 * {@link CouponIssuedEventPublishException} 을 던지고, 호출자(OutboxPoller)가 markPublished
 * 를 건너뛰어 다음 cycle 에서 재시도.
 *
 * <p>partition key = userId — 같은 user 의 여러 이벤트(향후 redeem/cancel 추가 시)가 같은
 * partition 에 모이므로 consumer 측 ordering 보장. eventId key 는 단일 이벤트(`event_id=1`)에
 * 트래픽 집중 시 partition skew 를 유발 → 거부.
 */
@Component
class KafkaCouponIssuedEventPublisher implements CouponIssuedEventPublisher {

    static final String HEADER_IDEMPOTENCY_KEY = "idempotencyKey";
    static final String HEADER_COUPON_CODE = "couponCode";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final long sendTimeoutMs;

    KafkaCouponIssuedEventPublisher(
        KafkaTemplate<String, String> kafkaTemplate,
        ObjectMapper objectMapper,
        @Value("${app.kafka.topic.coupon-issued}") String topic,
        @Value("${app.outbox.poller.send-timeout-ms}") long sendTimeoutMs
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Override
    public void publish(CouponIssueOutbox outbox) {
        CouponIssuedEvent event = outbox.getEvent();
        String value = serialize(event);
        String key = String.valueOf(event.userId());

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, key, value);
        Headers headers = record.headers();
        headers.add(new RecordHeader(
            HEADER_IDEMPOTENCY_KEY, event.idempotencyKey().getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(
            HEADER_COUPON_CODE, event.couponCode().value().getBytes(StandardCharsets.UTF_8)));

        try {
            kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CouponIssuedEventPublishException(
                "kafka publish interrupted: couponCode=" + event.couponCode().value(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new CouponIssuedEventPublishException(
                "kafka publish failed: couponCode=" + event.couponCode().value(), e);
        }
    }

    private String serialize(CouponIssuedEvent event) {
        CouponIssuedEventPayload payload = new CouponIssuedEventPayload(
            event.eventId(),
            event.userId(),
            event.couponCode().value(),
            event.idempotencyKey(),
            event.issuedAt()
        );
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // record 직렬화는 정상적으론 실패 불가 — 발생 시 시스템 결함.
            throw new IllegalStateException("kafka payload serialization failed", e);
        }
    }
}
