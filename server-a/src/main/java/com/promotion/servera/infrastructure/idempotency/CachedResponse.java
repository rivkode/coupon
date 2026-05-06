package com.promotion.servera.infrastructure.idempotency;

/**
 * 캐시된 HTTP 응답 스냅샷. {@code body} 는 UTF-8 문자열 그대로 보관 (현재 모든 응답이 JSON).
 */
public record CachedResponse(int status, String body) {}
