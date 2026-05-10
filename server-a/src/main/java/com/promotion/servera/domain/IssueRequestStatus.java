package com.promotion.servera.domain;

/**
 * Server A 의 요청 로그 상태. 신규 설계는 응답이 즉시 "접수 완료" 라 진행 상태(FORWARDED 등) 가 의미 작다.
 *
 * <p>SOLD_OUT 은 ADR-011 negative cache 단락 결과 — B가 매진을 즉시 알려준 audit.
 */
public enum IssueRequestStatus {
    ACCEPTED,
    DUPLICATE,
    SOLD_OUT,
    REJECTED
}
