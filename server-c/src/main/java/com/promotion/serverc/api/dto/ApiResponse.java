package com.promotion.serverc.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 공통 응답 봉투 (server-a 와 동일 형식). 성공 시 data 만, 실패 시 error 만.
 *
 * <p>현재 server-a / server-c 양쪽에 동일 코드가 존재 — 후속 PR 에서 common 모듈로 통합 예정.
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
