package com.promotion.serverb.api.internal.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * server-a 가 호출하는 internal 발급 요청 본문.
 * userId 는 X-User-Id 헤더로 전달 (헤더 일관성).
 */
public record IssueRequest(
        @NotNull @Positive Long eventId,
        @NotNull @Positive Long couponTypeId
) {
}
