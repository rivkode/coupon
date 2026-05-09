package com.promotion.serverc.application;

import com.promotion.serverc.domain.OutboxEventStatus;
import com.promotion.serverc.infrastructure.kafka.IssueResultPublisher;
import com.promotion.serverc.infrastructure.persistence.OutboxEventJpaEntity;
import com.promotion.serverc.infrastructure.persistence.OutboxEventJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * outbox_event → Kafka publish (ADR-002).
 *
 * <p>주기적으로 PENDING 상태의 outbox row 를 fetch → KafkaTemplate 으로 publish → 성공 시
 * status = PUBLISHED 로 갱신. publish 실패 시 status 유지 → 다음 주기에 재시도.
 *
 * <p>at-least-once 보장. 중복 publish 는 B 의 Redis 갱신이 idempotent 라 안전.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventJpaRepository repository;
    private final IssueResultPublisher publisher;
    private final int batchSize;

    public OutboxPoller(OutboxEventJpaRepository repository,
                        IssueResultPublisher publisher,
                        @Value("${app.outbox.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    @Transactional
    public void poll() {
        List<OutboxEventJpaEntity> events = repository.findByStatusOrderByCreatedAtAsc(
                OutboxEventStatus.PENDING, PageRequest.of(0, batchSize));

        if (events.isEmpty()) {
            return;
        }

        for (OutboxEventJpaEntity event : events) {
            try {
                publisher.publish(event.getAggregateId(), event.getPayload());
                event.markPublished(LocalDateTime.now());
            } catch (Exception ex) {
                // publish 실패 — status 유지 (PENDING) → 다음 주기 재시도.
                log.warn("outbox publish failed (will retry): id={}, type={}",
                        event.getOutboxEventId(), event.getEventType(), ex);
            }
        }
    }
}
