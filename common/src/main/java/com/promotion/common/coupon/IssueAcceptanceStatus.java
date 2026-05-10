package com.promotion.common.coupon;

/** A↔B 응답 상태. 신규 설계의 응답 모델은 즉시 "접수 완료" 만. */
public enum IssueAcceptanceStatus {
    ACCEPTED,
    DUPLICATE,
    /** ADR-011 — B 의 negative cache 가 매진을 즉시 단락한 결과. 사용자는 폴링 없이 SOLD_OUT 확정. */
    SOLD_OUT,
    INTERNAL_ERROR
}
