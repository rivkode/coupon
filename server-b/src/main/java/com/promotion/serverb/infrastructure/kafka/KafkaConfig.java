package com.promotion.serverb.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Outbox 발행 Kafka 설정 (CLAUDE.md ADR-002).
 *
 * <p>{@code spring.kafka.producer.*} (acks/idempotence/serializer) 는 application.yml 에서 주입.
 * 본 클래스는 topic 생성만 담당 — Spring Boot 의 KafkaAutoConfiguration 이 ProducerFactory /
 * KafkaTemplate / KafkaAdmin 빈을 자동 구성하므로 명시 정의 불필요.
 *
 * <p>NewTopic 빈은 KafkaAdmin 이 부팅 시 자동 생성 (broker 에 없으면). 운영에선 별도 cli 로
 * 사전 생성하는 것이 권장이지만, 본 과제 5 일 일정에선 자동 생성 + README 트레이드오프 명시.
 */
@Configuration
class KafkaConfig {

    private final String couponIssuedTopic;
    private final int partitions;
    private final short replicationFactor;

    KafkaConfig(
        @Value("${app.kafka.topic.coupon-issued}") String couponIssuedTopic,
        @Value("${app.kafka.topic.coupon-issued-partitions}") int partitions,
        @Value("${app.kafka.topic.coupon-issued-replication-factor}") short replicationFactor
    ) {
        this.couponIssuedTopic = couponIssuedTopic;
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
    }

    @Bean
    NewTopic couponIssuedTopic() {
        return TopicBuilder.name(couponIssuedTopic)
            .partitions(partitions)
            .replicas(replicationFactor)
            .build();
    }
}
