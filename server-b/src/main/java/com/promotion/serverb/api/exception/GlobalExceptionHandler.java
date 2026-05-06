package com.promotion.serverb.api.exception;

import com.promotion.serverb.api.internal.dto.IssueResponse;
import com.promotion.serverb.application.IssueTemporarilyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * server-b 의 전역 예외 핸들러.
 *
 * <p>모든 응답 본문을 {@link IssueResponse} 로 통일 — server-a 의 RestClient 가 정상/실패 모두
 * 같은 record 로 받는다. HTTP status 만 다르게 분기.
 *
 * <p>매핑:
 * <ul>
 *   <li>MethodArgumentNotValidException → 400 (validation 실패)</li>
 *   <li>MissingRequestHeaderException → 400 (Idempotency-Key 누락)</li>
 *   <li>HttpMessageNotReadableException → 400 (잘못된 JSON)</li>
 *   <li>MethodArgumentTypeMismatchException → 400</li>
 *   <li>IllegalArgumentException → 400 (도메인 인자 검증 / header-body mismatch)</li>
 *   <li>IssueTemporarilyUnavailableException → 503 + Retry-After (보상 완료)</li>
 *   <li>IllegalStateException → 500 (CODE_COLLISION 재시도 소진 등 시스템 결함)</li>
 *   <li>그 외 RuntimeException → 500 (메시지 마스킹)</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 보상 완료 후 503 응답의 Retry-After (초). server-a 의 IssueRequestController (5 초) 보다 짧게
     * 설정 — server-b 의 503 은 일시적 RDB 장애 후 보상 완료 상태이므로 즉시 재시도가 안전하고
     * 빠르다.
     */
    private static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<IssueResponse> handleValidation(MethodArgumentNotValidException ex) {
        String reason = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(fe -> "VALIDATION_FAILED:" + fe.getField() + ":" + fe.getDefaultMessage())
            .orElse("VALIDATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(IssueResponse.failure(reason));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<IssueResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(IssueResponse.failure("MISSING_HEADER:" + ex.getHeaderName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<IssueResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(IssueResponse.failure("MALFORMED_BODY"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<IssueResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(IssueResponse.failure("TYPE_MISMATCH:" + ex.getName()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<IssueResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(IssueResponse.failure("INVALID_ARGUMENT:" + ex.getMessage()));
    }

    @ExceptionHandler(IssueTemporarilyUnavailableException.class)
    public ResponseEntity<IssueResponse> handleTemporarilyUnavailable(
        IssueTemporarilyUnavailableException ex
    ) {
        // 보상은 이미 service 에서 완료. 사용자에게는 retry 안내.
        log.warn("issue temporarily unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
            .body(IssueResponse.failure("compensated"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<IssueResponse> handleIllegalState(IllegalStateException ex) {
        log.error("illegal state in coupon issue", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(IssueResponse.failure("INTERNAL_STATE:" + ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<IssueResponse> handleGeneric(Exception ex) {
        log.error("unhandled exception in coupon issue", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(IssueResponse.failure("INTERNAL_ERROR"));
    }
}
