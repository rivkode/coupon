package com.promotion.serverc.infrastructure.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** OutboxPoller 가 호출하는 Kafka publish — coupon-issue-result 토픽. */
@Component
public class IssueResultPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final long sendTimeoutMs;

    public IssueResultPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                @Value("${app.kafka.topic.issue-result:coupon-issue-result}") String topic,
                                @Value("${app.kafka.send-timeout-ms:3000}") long sendTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    public void publish(String key, String payload) {
        try {
            kafkaTemplate.send(topic, key, payload).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("kafka send interrupted", ie);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("kafka send failed", ex);
        }
    }
}
