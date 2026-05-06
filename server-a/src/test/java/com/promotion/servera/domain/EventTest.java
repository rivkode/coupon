package com.promotion.servera.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventTest {

    private static final Instant START = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-05-31T00:00:00Z");

    @Test
    @DisplayName("정상 reconstitute 후 isOpenAt 이 [start, end) 범위에서 true")
    void open_at_range() {
        Event event = Event.reconstitute(1L, "Concert Pre-Sale", 10_000, START, END);

        assertThat(event.isOpenAt(START)).isTrue();              // 시작 시점 inclusive
        assertThat(event.isOpenAt(START.plusSeconds(86_400))).isTrue();
        assertThat(event.isOpenAt(START.minusSeconds(1))).isFalse();   // 시작 전
        assertThat(event.isOpenAt(END)).isFalse();                     // 종료 시점 exclusive
        assertThat(event.isOpenAt(END.plusSeconds(1))).isFalse();      // 종료 후
    }

    @Test
    void rejects_non_positive_totalStock() {
        assertThatThrownBy(() -> Event.reconstitute(1L, "n", 0, START, END))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("totalStock");
        assertThatThrownBy(() -> Event.reconstitute(1L, "n", -1, START, END))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_invalid_period() {
        // start == end
        assertThatThrownBy(() -> Event.reconstitute(1L, "n", 10, START, START))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("startedAt must be < endedAt");
        // start > end
        assertThatThrownBy(() -> Event.reconstitute(1L, "n", 10, END, START))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null_arguments() {
        assertThatThrownBy(() -> Event.reconstitute(1L, null, 10, START, END))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Event.reconstitute(1L, "n", 10, null, END))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Event.reconstitute(1L, "n", 10, START, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void isOpenAt_rejects_null_now() {
        Event event = Event.reconstitute(1L, "n", 10, START, END);
        assertThatThrownBy(() -> event.isOpenAt(null))
            .isInstanceOf(NullPointerException.class);
    }
}
