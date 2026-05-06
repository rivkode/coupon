package com.promotion.serverb.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockTest {

    @Test
    @DisplayName("균등 분배 — 10000 / 10 = 1000 each")
    void distributes_evenly_when_no_remainder() {
        Stock stock = new Stock(1L, 10_000, 10);

        for (int i = 0; i < 10; i++) {
            assertThat(stock.quantityFor(i)).isEqualTo(1000);
        }
    }

    @Test
    @DisplayName("나머지 분배 — 10003 / 10 → 앞 3샤드는 1001, 나머지 7샤드는 1000. 합은 totalQuantity 와 일치")
    void distributes_remainder_to_front_shards() {
        Stock stock = new Stock(1L, 10_003, 10);

        int sum = 0;
        for (int i = 0; i < 10; i++) {
            int q = stock.quantityFor(i);
            sum += q;
            assertThat(q).isEqualTo(i < 3 ? 1001 : 1000);
        }
        assertThat(sum).isEqualTo(10_003);
    }

    @Test
    void of_uses_redis_keys_shard_count() {
        Stock stock = Stock.of(1L, 100);
        assertThat(stock.shardCount()).isEqualTo(10);
    }

    @Test
    void rejects_invalid_event_id() {
        assertThatThrownBy(() -> new Stock(0L, 100, 10))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_total_smaller_than_shard_count() {
        // 5 장을 10 샤드로 나누면 일부 샤드가 0 이 됨 — 그 샤드를 친 사용자는 무조건 매진을 보게 되어 부정확.
        assertThatThrownBy(() -> new Stock(1L, 5, 10))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_shard_id_out_of_range() {
        Stock stock = new Stock(1L, 100, 10);
        assertThatThrownBy(() -> stock.quantityFor(10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.quantityFor(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
