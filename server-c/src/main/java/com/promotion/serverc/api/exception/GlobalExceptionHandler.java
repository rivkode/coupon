package com.promotion.serverc.api.exception;

import com.promotion.serverc.api.dto.ApiResponse;
import com.promotion.serverc.api.dto.ErrorResponse;
import com.promotion.serverc.api.dto.ErrorResponse.FieldError;
import com.promotion.serverc.domain.exception.CouponNotFoundException;
import com.promotion.serverc.domain.exception.EventNotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 전역 예외 핸들러. server-a 의 핸들러와 동일 형식 + redeem 영역의 예외 (낙관락, 도메인 NotFound) 매핑 추가.
 *
 * <p>매핑:
 * <ul>
 *   <li>{@link CouponNotFoundException} → 404 (코드 없음 / 소유권 마스킹 모두 동일)</li>
 *   <li>{@link OptimisticLockingFailureException} → 409 RACE_RETRY (재시도 안내)</li>
 *   <li>MethodArgumentNotValidException → 400 (필드별 에러)</li>
 *   <li>MissingRequestHeaderException → 400 (X-User-Id / Idempotency-Key 누락)</li>
 *   <li>HttpMessageNotReadableException → 400</li>
 *   <li>MethodArgumentTypeMismatchException → 400</li>
 *   <li>IllegalArgumentException → 400 (도메인 인자 검증 — 예: CouponCode 길이)</li>
 *   <li>IllegalStateException → 409 (도메인 상태 전이 실패 — 예: 이미 used 인데 redeem 호출)</li>
 *   <li>그 외 → 500 (메시지 마스킹 + ERROR 로그)</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CouponNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(CouponNotFoundException ex) {
        ErrorResponse error = ErrorResponse.of("NOT_FOUND", "coupon not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(error));
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEventNotFound(EventNotFoundException ex) {
        ErrorResponse error = ErrorResponse.of("EVENT_NOT_FOUND", "event not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(error));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.info("optimistic lock retry: {}", ex.getMessage());
        ErrorResponse error = ErrorResponse.of(
            "RACE_RETRY", "concurrent modification — retry the request");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure(error));
    }

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
