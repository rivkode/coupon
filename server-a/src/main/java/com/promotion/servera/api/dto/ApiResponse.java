package com.promotion.servera.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 공통 응답 봉투. 성공 시 data 만, 실패 시 error 만 채워진다.
 * 직렬화 시 null 필드는 생략 (`JsonInclude.Include.NON_NULL`).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorResponse error) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> failure(ErrorResponse error) {
        return new ApiResponse<>(false, null, error);
    }
}
