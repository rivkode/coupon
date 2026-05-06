package com.promotion.serverb.api.internal;

import com.promotion.common.coupon.IssueResult;
import com.promotion.serverb.api.internal.dto.IssueRequest;
import com.promotion.serverb.api.internal.dto.IssueResponse;
import com.promotion.serverb.application.CouponIssueService;
import com.promotion.serverb.application.IssueCommand;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * server-b 의 internal 발급 endpoint. server-a 가 호출.
 *
 * <p>외부 노출되지 않는 internal API — 별도 인증 없이 server-a 가 헤더 + body 로 사용자 컨텍스트 전달.
 * 헤더와 body 의 idempotencyKey 가 다르면 400 (validation 안전망).
 *
 * <p>HTTP 상태 매핑은 GlobalExceptionHandler 에 위임:
 * <ul>
 *   <li>ISSUED / ALREADY_ISSUED / SOLD_OUT → 200 (body 의 status 로 구분)</li>
 *   <li>validation 실패 / header-body mismatch → 400</li>
 *   <li>IssueTemporarilyUnavailableException (보상 후) → 503 + Retry-After</li>
 *   <li>IllegalStateException (CODE_COLLISION 재시도 소진 등) → 500</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/v1/coupons")
public class CouponIssueController {

    private final CouponIssueService service;

    public CouponIssueController(CouponIssueService service) {
        this.service = service;
    }

    @PostMapping("/issue")
    public IssueResponse issue(
        @RequestHeader("Idempotency-Key") String idempotencyKeyHeader,
        @Valid @RequestBody IssueRequest body
    ) {
        if (!idempotencyKeyHeader.equals(body.idempotencyKey())) {
            throw new IllegalArgumentException(
                "Idempotency-Key header does not match body.idempotencyKey");
        }
        IssueResult result = service.issue(new IssueCommand(
            body.eventId(), body.userId(), body.idempotencyKey()));
        return IssueResponse.from(result);
    }
}
