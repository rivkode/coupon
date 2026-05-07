package com.promotion.serverb.application;

/**
 * Outbox 발행 실패. poller 가 catch 한 뒤 markPublished 호출 안 함 → 다음 cycle 재시도.
 */
public class CouponIssuedEventPublishException extends RuntimeException {

    public CouponIssuedEventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
