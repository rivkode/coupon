package com.promotion.serverb.api.internal;

import com.promotion.common.coupon.IssueAcceptanceResult;
import com.promotion.serverb.api.internal.dto.IssueRequest;
import com.promotion.serverb.api.internal.dto.IssueResponse;
import com.promotion.serverb.application.CouponIssueAcceptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * server-a 가 호출하는 internal 발급 endpoint. 신규 설계 — 즉시 "접수 완료" 응답 (ADR-001).
 */
@RestController
@RequestMapping("/internal/v1/coupons")
@RequiredArgsConstructor
public class CouponIssueController {

    private final CouponIssueAcceptService service;

    @PostMapping("/issue")
    public IssueResponse issue(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody IssueRequest request
    ) {
        IssueAcceptanceResult result = service.accept(userId, request.eventId(), request.couponTypeId());
        return IssueResponse.from(result);
    }
}
