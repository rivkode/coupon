package com.promotion.serverc.application;

import java.util.Objects;

/** Redeem API 입력. ADR-004 — Idempotency-Key 헤더 미사용. */
public record RedeemCommand(String code, long userId) {

    public RedeemCommand {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive but was " + userId);
        }
    }
}
