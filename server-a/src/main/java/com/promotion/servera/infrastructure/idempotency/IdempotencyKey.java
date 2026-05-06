package com.promotion.servera.infrastructure.idempotency;

import java.util.Objects;

/**
 * 사용자 단위로 격리된 멱등성 키 ({@code idem:{userId}:{key}}).
 *
 * <p>다른 사용자가 우연히 같은 UUID 를 보내도 충돌하지 않도록 user-scoped. CLAUDE.md ADR-004.
 */
public record IdempotencyKey(long userId, String key) {

    public IdempotencyKey {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
    }

    public String redisKey() {
        return "idem:%d:%s".formatted(userId, key);
    }
}
