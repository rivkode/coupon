package com.promotion.serverb.it;

import org.testcontainers.containers.GenericContainer;

/**
 * IT 간 ApplicationContext 캐시 공유를 위해 Redis 컨테이너를 JVM lifetime 한 번만 시작.
 *
 * <p>각 IT 가 @Container 로 자기만의 컨테이너를 가지면 ApplicationContext 캐시가 첫 IT 의 redis
 * port 를 갖고 있는데, 첫 IT 종료 시 redis 컨테이너가 정리되어 두 번째 IT 가 dead redis 를 가리키게
 * 된다 (connection refused). 본 클래스가 static initializer 로 한 번만 시작 → 모든 IT 가 같은
 * 인스턴스 공유 + spring context 캐시 reuse 로 부팅 시간 절약.
 */
public final class SharedContainers {

    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static boolean started = false;

    private SharedContainers() {}

    public static synchronized void startAll() {
        if (!started) {
            REDIS.start();
            started = true;
        }
    }

    public static String redisHost() {
        return REDIS.getHost();
    }

    public static Integer redisPort() {
        return REDIS.getFirstMappedPort();
    }
}
