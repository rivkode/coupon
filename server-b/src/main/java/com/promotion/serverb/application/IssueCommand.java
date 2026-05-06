package com.promotion.serverb.application;

import java.util.Objects;

/**
 * Application 레이어 입력 커맨드. Controller 가 헤더 / 본문을 묶어 전달.
 */
public record IssueCommand(long eventId, long userId, String idempotencyKey) {

    public IssueCommand {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive: " + eventId);
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive: " + userId);
        }
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
