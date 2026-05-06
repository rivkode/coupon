package com.promotion.serverb.api.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * server-a 가 호출하는 internal 발급 요청 본문.
 *
 * <p>server-a 의 RestClientCouponIssuingClient 가 보내는 형식과 정합 — 변경 시 양쪽 동시 갱신.
 */
public record IssueRequest(
    @NotNull @Positive Long userId,
    @NotNull @Positive Long eventId,
    @NotBlank @Size(max = 64) String idempotencyKey
) {
}
