package com.promotion.serverb.application;

import com.promotion.serverb.domain.CouponIssueOutbox;

/**
 * Outbox 행 1건을 외부 메시지 시스템으로 발행하는 application 포트.
 * 구현은 infrastructure/kafka 에 위치 (헥사고날 의도가 아니라 application 이 인프라
 * 디테일에 의존하지 않게 하는 가벼운 분리 — CLAUDE.md §11 코딩 컨벤션).
 *
 * <p>구현체는 동기적으로 발행 결과를 확인해야 한다 (성공/실패 명시). poller 가 결과에 따라
 * markPublished 여부를 결정.
 */
public interface CouponIssuedEventPublisher {

    /**
     * @throws CouponIssuedEventPublishException 발행 실패 (timeout, broker down, etc.)
     */
    void publish(CouponIssueOutbox outbox);
}
