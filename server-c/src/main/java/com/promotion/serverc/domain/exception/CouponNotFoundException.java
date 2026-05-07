package com.promotion.serverc.domain.exception;

/**
 * 쿠폰 코드가 존재하지 않거나 요청 user 의 소유가 아닌 경우.
 *
 * <p>본 과제는 다른 user 의 쿠폰 시도를 동일하게 404 로 마스킹한다 (코드 존재 여부 누설 방지).
 * 따라서 이 예외 하나로 두 케이스를 모두 표현하며, 메시지에 사유를 구분해 ERROR 로깅하지만
 * 클라이언트 응답은 같다.
 */
public class CouponNotFoundException extends RuntimeException {

    public CouponNotFoundException(String message) {
        super(message);
    }
}
