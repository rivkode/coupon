package com.promotion.servera.infrastructure.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bucket4j 분산 토큰 버킷의 백엔드(Lettuce) 빈 구성.
 *
 * <p>Spring Boot 가 자동 구성하는 {@code RedisConnectionFactory} 는 Spring Data Redis 추상화에
 * 묶여 있어 Bucket4j 가 요구하는 raw Lettuce {@code StatefulRedisConnection<String, byte[]>}
 * 와 codec / 키 타입이 다르다. 따라서 Bucket4j 전용 RedisClient + Connection 을 별도로 구성하고
 * 종료 훅에서 정리한다.
 *
 * <p>버킷 키 만료 정책: {@code basedOnTimeForRefillingBucketUpToMax} — 버킷이 최대 토큰까지
 * 회복되는 데 필요한 시간만 보관 → 비활성 사용자 키 자동 정리.
 */
@Configuration
public class Bucket4jConfig {

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, byte[]> connection;

    public Bucket4jConfig(
        @Value("${spring.data.redis.host:localhost}") String host,
        @Value("${spring.data.redis.port:6379}") int port,
        @Value("${spring.data.redis.timeout:1000ms}") Duration timeout
    ) {
        RedisURI uri = RedisURI.builder()
            .withHost(host)
            .withPort(port)
            .withTimeout(timeout)
            .build();
        this.redisClient = RedisClient.create(uri);
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        this.connection = redisClient.connect(codec);
    }

    @Bean
    public ProxyManager<String> bucket4jProxyManager() {
        return Bucket4jLettuce
            .casBasedBuilder(connection)
            .expirationAfterWrite(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10)))
            .build();
    }

    @PreDestroy
    public void shutdown() {
        connection.close();
        redisClient.shutdown();
    }
}
