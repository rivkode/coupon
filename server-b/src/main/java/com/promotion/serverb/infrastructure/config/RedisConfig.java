package com.promotion.serverb.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 설정. Lettuce ConnectionFactory 는 Spring Boot autoconfiguration 이 application.yml
 * (`spring.data.redis.*`) 기반으로 자동 생성한다 — 여기선 별도 빈 정의 없이 StringRedisTemplate
 * 만 명시적으로 노출한다.
 *
 * <p>PR #9 의 Redis Lua atomic 재고 차감 (CLAUDE.md ADR-003) 도 String 직렬화로 충분 (KEYS / ARGV
 * 가 모두 문자열). 향후 객체 직렬화가 필요해지면 별도 RedisTemplate 빈 추가.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
