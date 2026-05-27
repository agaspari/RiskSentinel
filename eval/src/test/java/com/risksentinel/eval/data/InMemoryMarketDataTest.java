package com.risksentinel.eval.data;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMarketDataTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    private static Bar bar(String sym, Instant ts) {
        return new Bar(sym, ts, 100.0, 101.0, 99.0, 100.0, 1L);
    }

    @Test
    void shouldIterateInTimestampOrder_whenInputIsShuffled() {
        InMemoryMarketData data = new InMemoryMarketData(List.of(
                bar("A", T0.plusSeconds(20)),
                bar("A", T0),
                bar("A", T0.plusSeconds(10))));
        List<Bar> out = new ArrayList<>();
        data.forEach(out::add);
        assertThat(out).extracting(Bar::timestamp)
                .containsExactly(T0, T0.plusSeconds(10), T0.plusSeconds(20));
    }

    @Test
    void shouldPreserveInputOrder_forBarsWithEqualTimestamps() {
        InMemoryMarketData data = new InMemoryMarketData(List.of(
                bar("Z", T0),
                bar("A", T0),
                bar("M", T0)));
        List<Bar> out = new ArrayList<>();
        data.forEach(out::add);
        assertThat(out).extracting(Bar::symbol).containsExactly("Z", "A", "M");
    }

    @Test
    void shouldExposeSize() {
        InMemoryMarketData data = new InMemoryMarketData(List.of(
                bar("A", T0), bar("A", T0.plusSeconds(1))));
        assertThat(data.size()).isEqualTo(2);
    }

    @Test
    void shouldAcceptEmpty() {
        InMemoryMarketData data = new InMemoryMarketData(List.of());
        assertThat(data.size()).isZero();
        assertThat(data.iterator().hasNext()).isFalse();
    }
}
