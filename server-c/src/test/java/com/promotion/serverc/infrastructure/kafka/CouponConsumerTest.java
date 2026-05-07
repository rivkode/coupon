package com.promotion.serverc.infrastructure.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.promotion.common.coupon.CouponIssuedEventPayload;
import com.promotion.serverc.application.CouponIngestService;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

class CouponConsumerTest {

    private static final String TOPIC = "coupon.issued";

    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("정상 메시지 — service.ingest 1회 호출, 예외 없음")
    void onMessage_invokes_service() throws Exception {
        CouponIngestService service = mock(CouponIngestService.class);
        doNothing().when(service).ingest(any(CouponIssuedEventPayload.class));
        CouponConsumer consumer = new CouponConsumer(service, objectMapper);

        CouponIssuedEventPayload payload = newPayload("idem-1", "ABCDEFGHJKMN");
        ConsumerRecord<String, String> record = newRecord(payload);

        consumer.onMessage(record);

        verify(service, times(1)).ingest(any(CouponIssuedEventPayload.class));
    }

    @Test
    @DisplayName("UNIQUE 위반 — DataIntegrityViolationException 흡수 (재시도 루프 차단)")
    void onMessage_swallows_unique_violation() throws Exception {
        CouponIngestService service = mock(CouponIngestService.class);
        doThrow(new DataIntegrityViolationException("dup")).when(service).ingest(any());
        CouponConsumer consumer = new CouponConsumer(service, objectMapper);

        CouponIssuedEventPayload payload = newPayload("idem-1", "ABCDEFGHJKMN");

        // 예외가 throw 되지 않아야 listener 가 ack — 의미적 exactly-once 의 권위.
        consumer.onMessage(newRecord(payload));
    }

    @Test
    @DisplayName("transient DB 예외 — throw (DefaultErrorHandler 가 retry)")
    void onMessage_propagates_transient_failure() throws Exception {
        CouponIngestService service = mock(CouponIngestService.class);
        doThrow(new DataAccessResourceFailureException("conn lost")).when(service).ingest(any());
        CouponConsumer consumer = new CouponConsumer(service, objectMapper);

        CouponIssuedEventPayload payload = newPayload("idem-1", "ABCDEFGHJKMN");

        try {
            consumer.onMessage(newRecord(payload));
            org.assertj.core.api.Assertions.fail("expected DataAccessResourceFailureException");
        } catch (DataAccessResourceFailureException expected) {
            // pass
        }
    }

    @Test
    @DisplayName("malformed JSON — service 호출 없이 ack (poison pill 흡수)")
    void onMessage_skips_malformed_payload() {
        CouponIngestService service = mock(CouponIngestService.class);
        CouponConsumer consumer = new CouponConsumer(service, objectMapper);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 0, "k", "{not json");

        consumer.onMessage(record);

        verify(service, never()).ingest(any());
    }

    @Test
    @DisplayName("길이가 12 가 아닌 couponCode JSON — record 검증 실패가 ValueInstantiationException 으로 wrap → 흡수")
    void onMessage_skips_invalid_coupon_code_length() {
        CouponIngestService service = mock(CouponIngestService.class);
        CouponConsumer consumer = new CouponConsumer(service, objectMapper);

        // couponCode 길이 5 — payload record 의 compact constructor 가 IllegalArgumentException →
        // Jackson 이 ValueInstantiationException (JsonProcessingException 하위) 으로 wrap.
        String malformed = "{\"eventId\":1,\"userId\":4242,\"couponCode\":\"SHORT\","
            + "\"idempotencyKey\":\"idem-1\",\"issuedAt\":\"2026-05-07T10:00:00Z\"}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 0, "k", malformed);

        consumer.onMessage(record);

        verify(service, never()).ingest(any());
    }

    private CouponIssuedEventPayload newPayload(String idem, String code) {
        return new CouponIssuedEventPayload(
            1L, 4242L, code, idem, Instant.parse("2026-05-07T10:00:00Z"));
    }

    private ConsumerRecord<String, String> newRecord(CouponIssuedEventPayload payload)
        throws Exception {
        String value = objectMapper.writeValueAsString(payload);
        return new ConsumerRecord<>(TOPIC, 0, 0, String.valueOf(payload.userId()), value);
    }
}
