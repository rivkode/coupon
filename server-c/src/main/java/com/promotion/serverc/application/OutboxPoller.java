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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * outbox_event → Kafka publish (ADR-002).
 *
 * <p>주기적으로 PENDING 상태의 outbox row 를 fetch → KafkaTemplate 으로 publish → 성공한 건만
 * status = PUBLISHED 로 갱신. publish 실패 시 status 유지 → 다음 주기에 재시도.
 *
 * <p>이 메서드에 {@code @Transactional} 을 붙이지 않는다. 붙이면 batch 크기만큼의 Kafka publish
 * 왕복이 하나의 DB 트랜잭션 안에 들어가, 1 vCPU MySQL-C 의 커넥션을 발행 시간 내내 점유한다.
 * 같은 DB 에서 {@link CouponIssueProcessor} 가 재고 행에 비관적 락을 잡고 있으므로 그대로 경합이
 * 된다. Outbox 패턴의 목적 자체가 발행을 트랜잭션 밖으로 빼는 것이다 (CLAUDE.md §10).
 *
 * <p>대신 세 단계로 나눈다. 조회는 repository 의 짧은 read 트랜잭션, 발행은 트랜잭션 밖,
 * 상태 갱신은 성공한 id 만 모아 한 번의 bulk update.
 *
 * <p>at-least-once 보장. 상태 갱신 전에 죽으면 같은 결과가 다시 발행되지만, B 의 Redis 갱신이
 * idempotent 라 안전하다.
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
    public void poll() {
        List<OutboxEventJpaEntity> events = repository.findByStatusOrderByCreatedAtAsc(
                OutboxEventStatus.PENDING, PageRequest.of(0, batchSize));

        if (events.isEmpty()) {
            return;
        }

        List<Long> published = new ArrayList<>(events.size());
        for (OutboxEventJpaEntity event : events) {
            try {
                publisher.publish(event.getAggregateId(), event.getPayload());
                published.add(event.getOutboxEventId());
            } catch (Exception ex) {
                // publish 실패 — status 유지 (PENDING) → 다음 주기 재시도.
                log.warn("outbox publish failed (will retry): id={}, type={}",
                        event.getOutboxEventId(), event.getEventType(), ex);
            }
        }

        if (!published.isEmpty()) {
            repository.markPublished(published, OutboxEventStatus.PUBLISHED, LocalDateTime.now());
        }
    }
}
