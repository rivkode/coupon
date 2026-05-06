package com.promotion.servera.application;

import com.promotion.servera.api.dto.IssueCouponRequest;

/**
 * Application 레이어 입력 커맨드. Controller 가 헤더(userId, idempotencyKey) 와 본문을 묶어 전달한다.
 * 본문의 컨텍스트 필드(deviceId 등) 는 Day 1 시점엔 IssueRequest 도메인에 저장하지 않으므로
 * 커맨드에서 제외 — 후속 PR 에서 audit 확장 시 추가.
 */
public record IssueCommand(Long userId, Long eventId, String idempotencyKey) {

    public static IssueCommand of(Long userId, String idempotencyKey, IssueCouponRequest request) {
        return new IssueCommand(userId, request.eventId(), idempotencyKey);
    }
}
