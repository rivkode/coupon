package com.promotion.serverb.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promotion.common.coupon.CouponIssueResultPayload;
import com.promotion.common.coupon.CouponIssueResultStatus;
import com.promotion.serverb.domain.IssuePendingStatus;
import com.promotion.serverb.infrastructure.redis.RedisIssueRequestStore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** C → B `coupon-issue-result` consumer. ADR-009. */
@Component
@RequiredArgsConstructor
public class CouponIssueResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueResultConsumer.class);

    private final ObjectMapper objectMapper;
    private final RedisIssueRequestStore store;

    @KafkaListener(
            topics = "${app.kafka.topic.issue-result:coupon-issue-result}",
            groupId = "coupon-result-server-b"
    )
    public void onMessage(String value) {
        CouponIssueResultPayload payload;
        try {
            payload = objectMapper.readValue(value, CouponIssueResultPayload.class);
        } catch (JsonProcessingException ex) {
            log.warn("malformed coupon-issue-result — skipping: {}", value, ex);
            return;
        }
        store.markResult(
                payload.userId(),
                payload.couponTypeId(),
                map(payload.status()),
                payload.couponCode()
        );
    }

    private static IssuePendingStatus map(CouponIssueResultStatus status) {
        return switch (status) {
            case SUCCESS -> IssuePendingStatus.SUCCESS;
            case SOLD_OUT -> IssuePendingStatus.SOLD_OUT;
            case FAILED -> IssuePendingStatus.FAILED;
        };
    }
}
