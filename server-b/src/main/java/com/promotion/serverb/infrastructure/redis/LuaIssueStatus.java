package com.promotion.serverb.infrastructure.redis;

/**
 * issue-coupon.lua 의 첫 번째 반환 값. CLAUDE.md ADR-003 / Lua 스크립트와 동기화.
 *
 * <p>본 enum 은 Lua 의 string 결과를 Java 타입으로 매핑. 호출자는 본 status 를 보고 분기:
 * <ul>
 *   <li>{@link #ISSUED} — 신규 발급, Outbox INSERT 진행</li>
 *   <li>{@link #ALREADY_ISSUED} — idem 캐시 hit, Outbox INSERT 안 함</li>
 *   <li>{@link #SOLD_OUT} — 재고 0, Outbox INSERT 안 함</li>
 *   <li>{@link #CODE_COLLISION} — 코드 충돌 (확률 ~ 0), 호출자가 새 코드로 재시도</li>
 * </ul>
 */
public enum LuaIssueStatus {
    ISSUED,
    ALREADY_ISSUED,
    SOLD_OUT,
    CODE_COLLISION;

    public static LuaIssueStatus fromLua(String value) {
        return switch (value) {
            case "ISSUED" -> ISSUED;
            case "ALREADY_ISSUED" -> ALREADY_ISSUED;
            case "SOLD_OUT" -> SOLD_OUT;
            case "CODE_COLLISION" -> CODE_COLLISION;
            default -> throw new IllegalStateException("unknown lua status: " + value);
        };
    }
}
