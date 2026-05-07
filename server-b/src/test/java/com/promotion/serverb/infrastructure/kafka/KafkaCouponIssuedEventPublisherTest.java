package com.promotion.serverb.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.promotion.common.coupon.CouponCode;
import com.promotion.serverb.application.CouponIssuedEventPublishException;
import com.promotion.serverb.domain.CouponIssueOutbox;
import com.promotion.serverb.domain.CouponIssuedEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaCouponIssuedEventPublisherTest {

    private static final String TOPIC = "coupon.issued";

    // Spring Boot 의 자동 구성된 ObjectMapper 빈과 정합 — JavaTimeModule + ISO-8601 (timestamp 비활성).
    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("정상 발행 — ProducerRecord 의 topic / key=userId / headers / value=JSON 검증")
    @SuppressWarnings("unchecked")
    void publish_sends_record_with_expected_shape() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(completedSendResult());

        KafkaCouponIssuedEventPublisher publisher =
            new KafkaCouponIssuedEventPublisher(template, objectMapper, TOPIC, 1000L);

        Instant issuedAt = Instant.parse("2026-05-07T10:00:00Z");
        CouponIssuedEvent event = new CouponIssuedEvent(
            1L, 4242L, new CouponCode("ABCDEFGHJKMN"), "idem-xyz", issuedAt);
        CouponIssueOutbox outbox = CouponIssueOutbox.create(event, issuedAt);

        publisher.publish(outbox);

        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();

        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo("4242");

        Header idem = record.headers().lastHeader(KafkaCouponIssuedEventPublisher.HEADER_IDEMPOTENCY_KEY);
        assertThat(idem).isNotNull();
        assertThat(new String(idem.value(), StandardCharsets.UTF_8)).isEqualTo("idem-xyz");

        Header code = record.headers().lastHeader(KafkaCouponIssuedEventPublisher.HEADER_COUPON_CODE);
        assertThat(code).isNotNull();
        assertThat(new String(code.value(), StandardCharsets.UTF_8)).isEqualTo("ABCDEFGHJKMN");

        // value 는 평탄화 JSON (CouponIssuedEventPayload 형식). couponCode 가 String 이어야 함.
        assertThat(record.value())
            .contains("\"eventId\":1")
            .contains("\"userId\":4242")
            .contains("\"couponCode\":\"ABCDEFGHJKMN\"")
            .contains("\"idempotencyKey\":\"idem-xyz\"")
            .contains("\"issuedAt\":\"2026-05-07T10:00:00Z\"");
    }

    @Test
    @DisplayName("send 결과가 timeout — CouponIssuedEventPublishException")
    @SuppressWarnings("unchecked")
    void publish_throws_on_timeout() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        // 결코 완료되지 않는 future → .get(timeoutMs) 가 TimeoutException 던짐
        CompletableFuture<SendResult<String, String>> never = new CompletableFuture<>();
        when(template.send(any(ProducerRecord.class))).thenReturn(never);

        KafkaCouponIssuedEventPublisher publisher =
            new KafkaCouponIssuedEventPublisher(template, objectMapper, TOPIC, 50L);

        CouponIssuedEvent event = new CouponIssuedEvent(
            1L, 1L, new CouponCode("ABCDEFGHJKMN"), "idem-1", Instant.now());
        CouponIssueOutbox outbox = CouponIssueOutbox.create(event, Instant.now());

        assertThatThrownBy(() -> publisher.publish(outbox))
            .isInstanceOf(CouponIssuedEventPublishException.class)
            .hasMessageContaining("kafka publish failed");
    }

    @Test
    @DisplayName("send 결과 future 가 ExecutionException — CouponIssuedEventPublishException")
    @SuppressWarnings("unchecked")
    void publish_throws_on_execution_exception() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(template.send(any(ProducerRecord.class))).thenReturn(failed);

        KafkaCouponIssuedEventPublisher publisher =
            new KafkaCouponIssuedEventPublisher(template, objectMapper, TOPIC, 1000L);

        CouponIssuedEvent event = new CouponIssuedEvent(
            1L, 1L, new CouponCode("ABCDEFGHJKMN"), "idem-1", Instant.now());
        CouponIssueOutbox outbox = CouponIssueOutbox.create(event, Instant.now());

        assertThatThrownBy(() -> publisher.publish(outbox))
            .isInstanceOf(CouponIssuedEventPublishException.class);
    }

    private CompletableFuture<SendResult<String, String>> completedSendResult() {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, null);
        RecordMetadata metadata = new RecordMetadata(
            new TopicPartition(TOPIC, 0), 0L, 0, System.currentTimeMillis(), 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(record, metadata));
    }
}
