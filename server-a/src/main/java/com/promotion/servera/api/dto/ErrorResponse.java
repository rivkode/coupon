package com.promotion.servera.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 표준 에러 페이로드.
 * <ul>
 *   <li>{@code code} — 식별자 (예: VALIDATION_FAILED, MISSING_HEADER, INTERNAL_ERROR)</li>
 *   <li>{@code message} — 사람이 읽는 메시지. 내부 stacktrace 노출 금지.</li>
 *   <li>{@code fieldErrors} — bean validation 실패 시에만 채움.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<FieldError> fieldErrors) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }

    public static ErrorResponse withFields(String code, String message, List<FieldError> fieldErrors) {
        return new ErrorResponse(code, message, fieldErrors);
    }

    public record FieldError(String field, String message) {}
}
