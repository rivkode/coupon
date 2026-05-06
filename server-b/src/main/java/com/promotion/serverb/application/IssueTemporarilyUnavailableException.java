package com.promotion.serverb.application;

/**
 * Outbox INSERT 실패 후 Redis 보상까지 완료된 상태에서 호출자에게 전파될 예외.
 *
 * <p>GlobalExceptionHandler 가 503 + Retry-After 로 매핑. 사용자는 잠시 후 재시도하면 정상 발급 가능.
 * 본 예외 발생 시점에서 시스템은 일관 상태로 복구된 상태 — 사용자 실패 응답이 안전한 결정.
 */
public class IssueTemporarilyUnavailableException extends RuntimeException {

    public IssueTemporarilyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
