package com.promotion.servera.api.exception;

import com.promotion.servera.api.dto.ApiResponse;
import com.promotion.servera.api.dto.ErrorResponse;
import com.promotion.servera.api.dto.ErrorResponse.FieldError;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 전역 예외 핸들러.
 *
 * <p>Day 3 redeem API 도입 시 BusinessException 계층(CouponAlreadyRedeemedException 등) 추가 예정.
 * 현재 PR(Day 1) 은 표준 JDK/Spring 예외만 매핑하며, 도메인 상태 위반은 IllegalStateException 으로 처리한다.
 *
 * <p>매핑:
 * <ul>
 *   <li>MethodArgumentNotValidException → 400 (필드별 에러)</li>
 *   <li>MissingRequestHeaderException → 400 (X-User-Id / Idempotency-Key 누락)</li>
 *   <li>HttpMessageNotReadableException → 400 (잘못된 JSON)</li>
 *   <li>MethodArgumentTypeMismatchException → 400 (헤더/path 타입 불일치)</li>
 *   <li>IllegalArgumentException → 400 (도메인 인자 검증 실패)</li>
 *   <li>IllegalStateException → 409 (도메인 상태 전이 실패)</li>
 *   <li>그 외 RuntimeException → 500 (메시지 마스킹 + ERROR 로그)</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
            .toList();
        ErrorResponse error = ErrorResponse.withFields(
            "VALIDATION_FAILED", "request body validation failed", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(error));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException ex) {
        ErrorResponse error = ErrorResponse.of(
            "MISSING_HEADER", "required header missing: " + ex.getHeaderName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(error));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        ErrorResponse error = ErrorResponse.of("MALFORMED_BODY", "request body is malformed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(error));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ErrorResponse error = ErrorResponse.of(
            "TYPE_MISMATCH", "argument type mismatch: " + ex.getName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(error));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        ErrorResponse error = ErrorResponse.of("INVALID_ARGUMENT", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(error));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        ErrorResponse error = ErrorResponse.of("INVALID_STATE", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure(error));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("unhandled exception", ex);
        ErrorResponse error = ErrorResponse.of("INTERNAL_ERROR", "internal server error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(error));
    }
}
