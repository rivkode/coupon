package com.promotion.common.coupon;

/** A↔B 응답 상태. 신규 설계의 응답 모델은 즉시 "접수 완료" 만. */
public enum IssueAcceptanceStatus {
    ACCEPTED,
    DUPLICATE,
    INTERNAL_ERROR
}
