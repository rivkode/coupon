package com.promotion.serverb.infrastructure.redis;

/**
 * compensate.lua 의 결과.
 *
 * <ul>
 *   <li>{@link #OK} — 보상 완료 (재고 INCR + 캐시 정리)</li>
 *   <li>{@link #SKIPPED} — idem 캐시가 다른 코드를 가리키므로 보상 생략 (다른 요청이 정상 발급한 상태)</li>
 * </ul>
 */
public enum CompensationResult {
    OK,
    SKIPPED;

    public static CompensationResult fromLua(String value) {
        return switch (value) {
            case "OK" -> OK;
            case "SKIPPED" -> SKIPPED;
            default -> throw new IllegalStateException("unknown lua compensation result: " + value);
        };
    }
}
