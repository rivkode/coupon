package com.promotion.serverc.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.common.coupon.CouponIssueRequestPayload;
import com.promotion.serverc.application.CouponIssueProcessor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** B → C `coupon-issue-request` consumer. ADR-003 의 비관적 락 트랜잭션을 호출. */
@Component
@RequiredArgsConstructor
public class CouponIssueRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueRequestConsumer.class);

    private final CouponIssueProcessor processor;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${app.kafka.topic.issue-request:coupon-issue-request}",
            groupId = "coupon-issue-server-c",
            concurrency = "${app.kafka.consumer.concurrency:1}"
    )
    public void onMessage(String value) {
        CouponIssueRequestPayload payload;
        try {
            payload = objectMapper.readValue(value, CouponIssueRequestPayload.class);
        } catch (JsonProcessingException ex) {
            log.warn("malformed coupon-issue-request — skipping: {}", value, ex);
            return;
        }
        processor.process(payload);
    }
}
