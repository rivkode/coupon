package com.promotion.serverb.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.common.coupon.CouponIssueRequestPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** B → C `coupon-issue-request` publisher. ADR-008 — idempotent producer + retries. */
@Component
public class IssueRequestPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final long sendTimeoutMs;
    private final long schedulerSendTimeoutMs;

    public IssueRequestPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${app.kafka.topic.issue-request:coupon-issue-request}") String topic,
                                 @Value("${app.kafka.send-timeout-ms:3000}") long sendTimeoutMs,
                                 @Value("${app.kafka.scheduler-send-timeout-ms:500}") long schedulerSendTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.sendTimeoutMs = sendTimeoutMs;
        this.schedulerSendTimeoutMs = schedulerSendTimeoutMs;
    }

    public void publish(CouponIssueRequestPayload payload) {
        publishWithTimeout(payload, sendTimeoutMs);
    }

    /**
     * ADR-008 스케줄러 재발행용 — 짧은 timeout. 기본 흐름의 3s 가 스케줄러 cycle (fixed-delay 1s) 대비
     * 너무 커서 batch × timeout 의 cumulative latency 가 cycle 을 압도하는 것을 방지.
     * 한 번 실패해도 다음 cycle 이 재시도하므로 짧게 잘라도 회복 가능.
     */
    public void publishForScheduler(CouponIssueRequestPayload payload) {
        publishWithTimeout(payload, schedulerSendTimeoutMs);
    }

    private void publishWithTimeout(CouponIssueRequestPayload payload, long timeoutMs) {
        String key = Long.toString(payload.userId());
        try {
            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(topic, key, json).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize issue-request payload", e);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("kafka send interrupted", ie);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("kafka send failed", ex);
        }
    }
}
