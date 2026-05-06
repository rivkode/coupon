package com.promotion.common.coupon;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * 쿠폰 코드 VO. Server B 발급 시점에 generate() 로 생성, Server C UNIQUE constraint 가 권위.
 * 길이 12, 대문자 + 숫자 (Crockford base32 의 일부).
 */
public record CouponCode(String value) {

    private static final int LENGTH = 12;
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public CouponCode {
        Objects.requireNonNull(value, "couponCode value must not be null");
        if (value.length() != LENGTH) {
            throw new IllegalArgumentException(
                "couponCode length must be %d but was %d".formatted(LENGTH, value.length()));
        }
        for (int i = 0; i < value.length(); i++) {
            if (ALPHABET.indexOf(value.charAt(i)) < 0) {
                throw new IllegalArgumentException(
                    "couponCode contains invalid character: " + value.charAt(i));
            }
        }
    }

    public static CouponCode generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return new CouponCode(sb.toString());
    }
}
