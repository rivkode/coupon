package com.promotion.serverc.api;

import com.promotion.serverc.api.dto.ApiResponse;
import com.promotion.serverc.api.dto.EventResponse;
import com.promotion.serverc.application.EventQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 이벤트 조회 API — 평가항목 ③ Hot Spot/Cache stampede 검증 대상. */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventQueryController {

    private final EventQueryService eventQueryService;

    @GetMapping("/{eventId}")
    public ResponseEntity<ApiResponse<EventResponse>> findOne(@PathVariable("eventId") long eventId) {
        return ResponseEntity.ok(ApiResponse.success(eventQueryService.findById(eventId)));
    }
}
