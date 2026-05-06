package com.promotion.servera.domain;

import java.time.Instant;
import java.util.Objects;
import lombok.Getter;

/**
 * 프로모션 이벤트 마스터.
 *
 * <p><b>주의</b>: Event.totalStock 은 운영 입력 시점의 스냅샷이며, 런타임 재고 차감의 권위가 아니다.
 * 실시간 재고는 Server B 의 Redis 샤드 합계가 권위 (ADR-003).
 * 본 객체는 (a) eventId 존재 검증, (b) 기간 검증 (isOpenAt) 용도.
 */
@Getter
public class Event {

    private Long id;
    private String name;
    private int totalStock;
    private Instant startedAt;
    private Instant endedAt;

    private Event() {
    }

    public static Event reconstitute(Long id, String name, int totalStock, Instant startedAt, Instant endedAt) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (totalStock <= 0) {
            throw new IllegalArgumentException("totalStock must be > 0 but was " + totalStock);
        }
        if (!startedAt.isBefore(endedAt)) {
            throw new IllegalArgumentException("startedAt must be < endedAt");
        }
        Event e = new Event();
        e.id = id;
        e.name = name;
        e.totalStock = totalStock;
        e.startedAt = startedAt;
        e.endedAt = endedAt;
        return e;
    }

    public boolean isOpenAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(startedAt) && now.isBefore(endedAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Event other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
