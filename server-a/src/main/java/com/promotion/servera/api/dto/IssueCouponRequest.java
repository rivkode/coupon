package com.promotion.servera.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 쿠폰 발급 요청 본문 (10 fields).
 *
 * <p>userId 는 본문이 아닌 {@code X-User-Id} 헤더로 전달된다. 본문은 발급 컨텍스트(채널 / 디바이스 /
 * 클라이언트 메타데이터)만 담는다.
 *
 * <p>10 fields:
 * <ol>
 *   <li>{@code eventId} — 발급 대상 이벤트</li>
 *   <li>{@code deviceId} — 단말 식별자</li>
 *   <li>{@code channel} — WEB / APP_IOS / APP_ANDROID 등</li>
 *   <li>{@code requestedAt} — 클라이언트 시각 (서버 시각과 분리, 감사용)</li>
 *   <li>{@code clientVersion} — 앱 / 웹 빌드 버전</li>
 *   <li>{@code region} — KR / US 등 ISO 국가코드</li>
 *   <li>{@code language} — ko / en 등 ISO 언어코드</li>
 *   <li>{@code marketingConsent} — 마케팅 수신 동의 (선택지의 일부)</li>
 *   <li>{@code campaignSource} — 유입 캠페인 (옵션)</li>
 *   <li>{@code metadata} — 추가 컨텍스트 (옵션, &le;1000 char)</li>
 * </ol>
 */
public record IssueCouponRequest(
    @NotNull @Positive Long eventId,
    @NotBlank @Size(max = 64) String deviceId,
    @NotBlank @Size(max = 32) String channel,
    @NotNull Instant requestedAt,
    @NotBlank @Size(max = 32) String clientVersion,
    @NotBlank @Size(max = 8) String region,
    @NotBlank @Size(max = 8) String language,
    @NotNull Boolean marketingConsent,
    @Size(max = 64) String campaignSource,
    @Size(max = 1000) String metadata
) {}
