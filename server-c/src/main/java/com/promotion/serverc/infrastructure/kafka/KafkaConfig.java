package com.promotion.serverc.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic issueRequestTopic(
            @Value("${app.kafka.topic.issue-request:coupon-issue-request}") String topic,
            @Value("${app.kafka.topic.partitions:3}") int partitions,
            @Value("${app.kafka.topic.replication-factor:1}") short rf
    ) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(rf).build();
    }

    @Bean
    public NewTopic issueResultTopic(
            @Value("${app.kafka.topic.issue-result:coupon-issue-result}") String topic,
            @Value("${app.kafka.topic.partitions:3}") int partitions,
            @Value("${app.kafka.topic.replication-factor:1}") short rf
    ) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(rf).build();
    }
}
