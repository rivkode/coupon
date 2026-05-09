package com.promotion.servera.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 발급 요청 본문 — 10 필드.
 *
 * <p>userId 는 본문이 아닌 {@code X-User-Id} 헤더로 전달된다 (인증 단순화 — JWT 미도입).
 * 본문은 발급 컨텍스트 (이벤트 / 쿠폰 / 시간 / 채널 / 디바이스 등) 만 담는다.
 *
 * <p>핵심 식별자 (country, eventId, couponTypeId, channel, issuedAt, expireAt) 는 필수 검증.
 * 보조 컨텍스트 (deviceId, clientVersion, language, marketingConsent) 는 선택.
 */
public record IssueCouponRequest(
        @NotBlank @Size(max = 8) String country,
        @NotNull @Positive Long eventId,
        @NotNull @Positive Long couponTypeId,
        @NotNull Instant issuedAt,
        @NotNull Instant expireAt,
        @NotBlank @Size(max = 32) String channel,
        @Size(max = 64) String deviceId,
        @Size(max = 32) String clientVersion,
        @Size(max = 8) String language,
        Boolean marketingConsent
) {
}
