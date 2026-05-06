package com.promotion.serverb.domain;

import com.promotion.common.coupon.CouponCode;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;

/**
 * Outbox Aggregate Root — B 가 atomic 재고 차감/코드 발급에 성공한 직후 적재되는 영속 사건.
 *
 * <p>흐름 (CLAUDE.md ADR-002):
 * <ol>
 *   <li>B: Redis Lua 로 재고 차감 + 쿠폰 코드 생성</li>
 *   <li>B: 본 Aggregate 생성 후 RDBMS INSERT (Outbox 적재)</li>
 *   <li>poller: published=false 행을 읽어 Kafka 발행</li>
 *   <li>poller: 발행 성공 시 markPublished(now) 호출 → published=true</li>
 * </ol>
 *
 * <p>불변식:
 * <ul>
 *   <li>published == true 이면 publishedAt != null</li>
 *   <li>published == false 이면 publishedAt == null</li>
 *   <li>한번 published=true 가 된 후에는 다시 false 로 되돌릴 수 없음 (멱등 발행 보장)</li>
 * </ul>
 *
 * <p>{@code event} 는 도메인 사건 record. JSON 직렬화는 infrastructure (Mapper) 가 영속 시점에
 * 처리하므로 도메인은 형식을 모른다.
 */
@Getter
public class CouponIssueOutbox {

    private Long id;
    private CouponIssuedEvent event;
    private boolean published;
    private Instant publishedAt;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    private CouponIssueOutbox() {
    }

    /**
     * 새 Outbox 적재. published=false 로 시작.
     */
    public static CouponIssueOutbox create(CouponIssuedEvent event, Instant now) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(now, "now");

        CouponIssueOutbox outbox = new CouponIssueOutbox();
        outbox.event = event;
        outbox.published = false;
        outbox.publishedAt = null;
        outbox.createdAt = now;
        outbox.updatedAt = now;
        return outbox;
    }

    /**
     * Mapper 가 JpaEntity → 도메인 변환 시 사용. 외부에서는 호출 금지.
     */
    public static CouponIssueOutbox reconstitute(
        Long id,
        CouponIssuedEvent event,
        boolean published,
        Instant publishedAt,
        Long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        if (published && publishedAt == null) {
            throw new IllegalStateException("published=true requires publishedAt non-null");
        }
        if (!published && publishedAt != null) {
            throw new IllegalStateException("published=false requires publishedAt null");
        }

        CouponIssueOutbox outbox = new CouponIssueOutbox();
        outbox.id = id;
        outbox.event = event;
        outbox.published = published;
        outbox.publishedAt = publishedAt;
        outbox.version = version;
        outbox.createdAt = createdAt;
        outbox.updatedAt = updatedAt;
        return outbox;
    }

    /** Outbox AR 의 멱등 식별자 — Server C UNIQUE constraint 와 정합 (ADR-004). */
    public String idempotencyKey() {
        return event.idempotencyKey();
    }

    /** 발급된 쿠폰 코드 — UNIQUE constraint 의 값. */
    public CouponCode couponCode() {
        return event.couponCode();
    }

    /**
     * Kafka 발행 완료 후 호출. 이미 published 상태에서 재호출은 거부 (poller 재처리 시 의도치 않은 publishedAt 갱신 방지).
     * 멱등 발행은 호출자(poller) 가 이전에 published=true 인 행을 스킵하는 방식으로 보장한다.
     */
    public void markPublished(Instant now) {
        Objects.requireNonNull(now, "now");
        if (this.published) {
            throw new IllegalStateException("already published: couponCode=" + couponCode().value());
        }
        this.published = true;
        this.publishedAt = now;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CouponIssueOutbox other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
