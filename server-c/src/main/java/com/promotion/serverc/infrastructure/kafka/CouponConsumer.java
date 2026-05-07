package com.promotion.serverc.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.common.coupon.CouponIssuedEventPayload;
import com.promotion.serverc.application.CouponIngestService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka {@code coupon.issued} 토픽 consumer (CLAUDE.md ADR-002 / §5.3).
 *
 * <p>흐름:
 * <ol>
 *   <li>Server B 의 Outbox poller 가 {@code CouponIssuedEventPayload} JSON 을 발행.</li>
 *   <li>본 listener 가 String 으로 수신 → ObjectMapper 로 record 역직렬화.</li>
 *   <li>{@link CouponIngestService#ingest} 호출 → 트랜잭션 안에서 INSERT.</li>
 *   <li>{@link DataIntegrityViolationException} (UNIQUE 위반) 발생 시 "이미 처리됨" 시그널로 흡수
 *       하여 ack — 동일 메시지의 무한 retry 차단 (의미적 exactly-once).</li>
 *   <li>{@link JsonProcessingException} (poison pill) 도 흡수 + ERROR 로깅 — 재처리해도 항상 실패할
 *       메시지를 retry 루프에서 제외 (수동 추적 시 ERROR 로그 + DLT 는 프로덕션 진화 방향).</li>
 * </ol>
 *
 * <p>그 외 예외 (transient DB error, broker connection issue 등) 는 throw — Spring Kafka 의
 * DefaultErrorHandler 가 retry 처리.
 *
 * <p>ack-mode 는 {@code RECORD} (application.yml) — 메서드 정상 종료 시 메시지 단위로 즉시 commit.
 */
@Component
@RequiredArgsConstructor
class CouponConsumer {

    private static final Logger log = LoggerFactory.getLogger(CouponConsumer.class);

    private final CouponIngestService couponIngestService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${app.kafka.topic.coupon-issued}",
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        CouponIssuedEventPayload payload;
        try {
            payload = objectMapper.readValue(record.value(), CouponIssuedEventPayload.class);
        } catch (JsonProcessingException e) {
            // 잘못된 JSON 은 retry 해도 항상 실패 — ack 후 운영 추적용 ERROR 로그.
            log.error(
                "skipping malformed payload: topic={}, partition={}, offset={}, key={}, raw={}",
                record.topic(), record.partition(), record.offset(), record.key(),
                record.value(), e);
            return;
        }

        try {
            couponIngestService.ingest(payload);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE constraint 위반 — 이미 처리된 메시지 (poller 재시도 또는 broker retry).
            // ack 하여 재시도 루프 차단 — 의미적 exactly-once 의 권위.
            log.info(
                "coupon already persisted, skipping: code={}, userId={}, idem={}",
                payload.couponCode(), payload.userId(), payload.idempotencyKey());
        }
    }
}
