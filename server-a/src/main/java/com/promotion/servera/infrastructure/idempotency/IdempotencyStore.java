package com.promotion.servera.infrastructure.idempotency;

import java.util.Optional;

/**
 * Idempotency-Key 기반 응답 캐시 추상화.
 *
 * <p>Server A 의 1차 멱등성 보장 (CLAUDE.md ADR-004).
 * Server C 의 {@code coupon.idempotency_key} UNIQUE 가 최종 보장이며, 본 store 는 사용자 응답을
 * 빠르게 재현하기 위한 캐시 역할.
 *
 * <p>구현체: {@link RedisIdempotencyStore} — Redis SETNX + TTL.
 */
public interface IdempotencyStore {

    /**
     * 캐시된 응답 조회. 미존재 시 {@code Optional.empty()}.
     */
    Optional<CachedResponse> find(IdempotencyKey key);

    /**
     * 캐시 저장. 동일 키 재호출 시 덮어쓰기 — 호출자가 멱등 시점을 결정.
     */
    void save(IdempotencyKey key, CachedResponse response);
}
