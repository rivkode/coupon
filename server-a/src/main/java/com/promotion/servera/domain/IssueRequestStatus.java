package com.promotion.servera.domain;

import java.util.Map;
import java.util.Set;

/**
 * Server A 내부의 IssueRequest 상태. 발급 결과 코드인 {@link com.promotion.common.coupon.IssueStatus}
 * 와는 별개 (혼동 주의).
 *
 * <pre>
 *   [생성] ─► RECEIVED ─► FORWARDED ─► SUCCEEDED   (정상)
 *                   │            └────► FAILED      (B 호출 후 실패)
 *                   └─────────────────► REJECTED    (Idempotency conflict / Rate limit / Validation)
 * </pre>
 *
 * SUCCEEDED / FAILED / REJECTED 는 종단(terminal). 재전이 금지.
 */
public enum IssueRequestStatus {
    RECEIVED,
    FORWARDED,
    SUCCEEDED,
    FAILED,
    REJECTED;

    private static final Map<IssueRequestStatus, Set<IssueRequestStatus>> ALLOWED = Map.of(
        RECEIVED, Set.of(FORWARDED, REJECTED),
        FORWARDED, Set.of(SUCCEEDED, FAILED),
        SUCCEEDED, Set.of(),
        FAILED, Set.of(),
        REJECTED, Set.of()
    );

    public boolean canTransitionTo(IssueRequestStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
