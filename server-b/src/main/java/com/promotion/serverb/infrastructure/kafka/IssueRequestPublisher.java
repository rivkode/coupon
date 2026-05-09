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

    public IssueRequestPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${app.kafka.topic.issue-request:coupon-issue-request}") String topic,
                                 @Value("${app.kafka.send-timeout-ms:3000}") long sendTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    public void publish(CouponIssueRequestPayload payload) {
        String key = Long.toString(payload.userId());
        try {
            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(topic, key, json).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
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
